package technology.tabula.detectors;

import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.util.ImageIOUtil;
import org.apache.pdfbox.util.PDFOperator;
import technology.tabula.*;
import technology.tabula.Rectangle;
import technology.tabula.extractors.BasicExtractionAlgorithm;

import java.awt.*;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.List;

/**
 * Created by matt on 2015-12-17.
 *
 * Attempt at an implementation of the table finding algorithm described by
 * Anssi Nurminen's master's thesis:
 * http://dspace.cc.tut.fi/dpub/bitstream/handle/123456789/21520/Nurminen.pdf?sequence=3
 */
public class NurminenDetectionAlgorithm implements DetectionAlgorithm {

    private static final int GRAYSCALE_INTENSITY_THRESHOLD = 25;
    private static final float TABLE_PADDING_AMOUNT = 1.0f;

    private static final Comparator<Point2D.Float> pointComparator = new Comparator<Point2D.Float>() {
        @Override
        public int compare(Point2D.Float o1, Point2D.Float o2) {
            if (o1.equals(o2)) {
                return 0;
            }

            if (o1.getY() == o2.getY()) {
                return Double.compare(o1.getX(), o2.getX());
            } else {
                return Double.compare(o1.getY(), o2.getY());
            }
        }
    };

    BufferedImage debugImage;
    String debugFileOut;

    @Override
    public List<Rectangle> detect(Page page, File referenceDocument) {

        // open a PDDocument to read stuff in
        PDDocument pdfDocument;
        try {
            pdfDocument = PDDocument.load(referenceDocument);
        } catch (Exception e) {
            return new ArrayList<Rectangle>();
        }

        // get the page in question
        PDPage pdfPage = (PDPage) pdfDocument.getDocumentCatalog().getAllPages().get(page.getPageNumber() - 1);

        // debugging stuff - spit out an image with what we want to see
        debugFileOut = referenceDocument.getAbsolutePath().replace(".pdf", "-" + page.getPageNumber() + ".jpg");

        BufferedImage image;
        try {
            image = pdfPage.convertToImage(BufferedImage.TYPE_BYTE_GRAY, 144);
            debugImage = pdfPage.convertToImage(BufferedImage.TYPE_INT_RGB, 72);
        } catch (IOException e) {
            return new ArrayList<Rectangle>();
        }

        List<Line2D.Float> horizontalRulings = this.getHorizontalRulings(image);

        // now check the page for vertical lines, but remove the text first to make things less confusing
        try {
            this.removeText(pdfDocument, pdfPage);
            image = pdfPage.convertToImage(BufferedImage.TYPE_BYTE_GRAY, 144);
        } catch (Exception e) {
            return new ArrayList<Rectangle>();
        }

        List<Line2D.Float> verticalRulings = this.getVerticalRulings(image);

        List<Line2D.Float> allEdges = new ArrayList<Line2D.Float>(horizontalRulings);
        allEdges.addAll(verticalRulings);

        List<Rectangle> tableAreas = new ArrayList<Rectangle>();
        List<Rectangle> cells = null;
        Set<Point2D.Float> crossingPoints = null;

        // if we found some edges, try to find some tables based on them
        if (allEdges.size() > 0) {
            // now we need to snap edge endpoints to a grid
            Utils.snapPoints(allEdges, 8f, 8f);

            // next get the crossing points of all the edges
            crossingPoints = this.getCrossingPoints(horizontalRulings, verticalRulings);

            // merge the edge lines into rulings - this makes finding edges between crossing points in the next step easier
            horizontalRulings = this.mergeHorizontalEdges(horizontalRulings);
            verticalRulings = this.mergeVerticalEdges(verticalRulings);

            // use the rulings and points to find cells
            cells = this.findRectangles(crossingPoints, horizontalRulings, verticalRulings);

            // then use those cells to make table areas
            tableAreas = this.getTableAreasFromCells(cells);
        }

        // now find text alignments in the document
        List<TextChunk> textChunks = TextElement.mergeWords(page.getText());
        List<Line> lines = TextChunk.groupByLines(textChunks);

        List<Line2D.Float> leftTextEdges = new ArrayList<Line2D.Float>();
        List<Line2D.Float> midTextEdges = new ArrayList<Line2D.Float>();
        List<Line2D.Float> rightTextEdges = new ArrayList<Line2D.Float>();

        Map<Integer, List<TextChunk>> currLeftEdges = new HashMap<Integer, List<TextChunk>>();
        Map<Integer, List<TextChunk>> currMidEdges = new HashMap<Integer, List<TextChunk>>();
        Map<Integer, List<TextChunk>> currRightEdges = new HashMap<Integer, List<TextChunk>>();

        for (Line textRow : lines) {
            for (TextChunk text : textRow.getTextElements()) {
                Integer left = new Integer((int)Math.floor(text.getLeft()));
                Integer right = new Integer((int)Math.floor(text.getRight()));
                Integer mid = new Integer(left + ((right - left)/2));

                // first put this chunk into any edge buckets it belongs to
                List<TextChunk> leftEdge = currLeftEdges.getOrDefault(left, new ArrayList<TextChunk>());
                leftEdge.add(text);
                currLeftEdges.put(left, leftEdge);

                List<TextChunk> midEdge = currMidEdges.getOrDefault(mid, new ArrayList<TextChunk>());
                midEdge.add(text);
                currMidEdges.put(mid, midEdge);

                List<TextChunk> rightEdge = currRightEdges.getOrDefault(right, new ArrayList<TextChunk>());
                rightEdge.add(text);
                currRightEdges.put(right, rightEdge);

                // now see if this text chunk blows up any other edges
                for (Iterator<Map.Entry<Integer, List<TextChunk>>> iterator = currLeftEdges.entrySet().iterator(); iterator.hasNext();) {
                    Map.Entry<Integer, List<TextChunk>> entry = iterator.next();
                    Integer key = entry.getKey();
                    if (key > left && key < right) {
                        iterator.remove();
                        List<TextChunk> edgeChunks = entry.getValue();
                        if (edgeChunks.size() >= 4) {
                            TextChunk first = edgeChunks.get(0);
                            TextChunk last = edgeChunks.get(edgeChunks.size() - 1);
                            leftTextEdges.add(new Line2D.Float(key, first.getTop(), key, last.getBottom()));
                        }
                    }
                }

                for (Iterator<Map.Entry<Integer, List<TextChunk>>> iterator = currMidEdges.entrySet().iterator(); iterator.hasNext();) {
                    Map.Entry<Integer, List<TextChunk>> entry = iterator.next();
                    Integer key = entry.getKey();
                    if (key > left && key < right && Math.abs(key - mid) > 2) {
                        iterator.remove();
                        List<TextChunk> edgeChunks = entry.getValue();
                        if (edgeChunks.size() >= 4) {
                            TextChunk first = edgeChunks.get(0);
                            TextChunk last = edgeChunks.get(edgeChunks.size() - 1);
                            midTextEdges.add(new Line2D.Float(key, first.getTop(), key, last.getBottom()));
                        }
                    }
                }

                for (Iterator<Map.Entry<Integer, List<TextChunk>>> iterator = currRightEdges.entrySet().iterator(); iterator.hasNext();) {
                    Map.Entry<Integer, List<TextChunk>> entry = iterator.next();
                    Integer key = entry.getKey();
                    if (key > left && key < right) {
                        iterator.remove();
                        List<TextChunk> edgeChunks = entry.getValue();
                        if (edgeChunks.size() >= 4) {
                            TextChunk first = edgeChunks.get(0);
                            TextChunk last = edgeChunks.get(edgeChunks.size() - 1);
                            rightTextEdges.add(new Line2D.Float(key, first.getTop(), key, last.getBottom()));
                        }
                    }
                }
            }
        }

        // add the leftovers
        for (Integer key : currLeftEdges.keySet()) {
            List<TextChunk> edgeChunks = currLeftEdges.get(key);
            if (edgeChunks.size() >= 4) {
                TextChunk first = edgeChunks.get(0);
                TextChunk last = edgeChunks.get(edgeChunks.size() - 1);
                leftTextEdges.add(new Line2D.Float(key, first.getTop(), key, last.getBottom()));
            }
        }

        for (Integer key : currMidEdges.keySet()) {
            List<TextChunk> edgeChunks = currMidEdges.get(key);
            if (edgeChunks.size() >= 4) {
                TextChunk first = edgeChunks.get(0);
                TextChunk last = edgeChunks.get(edgeChunks.size() - 1);
                midTextEdges.add(new Line2D.Float(key, first.getTop(), key, last.getBottom()));
            }
        }

        for (Integer key : currRightEdges.keySet()) {
            List<TextChunk> edgeChunks = currRightEdges.get(key);
            if (edgeChunks.size() >= 4) {
                TextChunk first = edgeChunks.get(0);
                TextChunk last = edgeChunks.get(edgeChunks.size() - 1);
                rightTextEdges.add(new Line2D.Float(key, first.getTop(), key, last.getBottom()));
            }
        }

        //this.debug(lines);

        this.debug(leftTextEdges);
        this.debug(midTextEdges);
        this.debug(rightTextEdges);

        // next find any vertical rulings that intersect tables - sometimes these won't have completely been captured as
        // cells if there are missing horizontal lines (which there often are)
        // let's assume though that these lines should be part of the table
        //Map<Line2D.Float, Rectangle> verticalTableRulings = new HashMap<Line2D.Float, Rectangle>();
        for (Line2D.Float verticalRuling : verticalRulings) {
            for (Rectangle tableArea : tableAreas) {
                if (verticalRuling.intersects(tableArea) &&
                        !(tableArea.contains(verticalRuling.getP1()) && tableArea.contains(verticalRuling.getP2()))) {

                    //verticalTableRulings.put(verticalRuling, tableArea);
                    tableArea.setTop((float)Math.floor(Math.min(tableArea.getTop(), verticalRuling.getY1())));
                    tableArea.setBottom((float)Math.ceil(Math.max(tableArea.getBottom(), verticalRuling.getY2())));
                    break;
                }
            }
        }

        // the tabula Page coordinate space is half the size of the PDFBox image coordinate space
        // so halve the table area size before proceeding and add a bit of padding to make sure we capture everything
        for (Rectangle area : tableAreas) {
            area.x = (float)Math.floor(area.x/2) - TABLE_PADDING_AMOUNT;
            area.y = (float)Math.floor(area.y/2) - TABLE_PADDING_AMOUNT;
            area.width = (float)Math.ceil(area.width/2) + TABLE_PADDING_AMOUNT;
            area.height = (float)Math.ceil(area.height/2) + TABLE_PADDING_AMOUNT;
        }

        // now look at each text row and see what kind of and how many vertical rulings it intersects
        // this will help us figure out what text rows should be part of a table

        // first look for text rows that intersect an existing table - those lines should probably be part of the table
        for (Line textRow : lines) {
            for (Rectangle tableArea : tableAreas) {
                if (!tableArea.contains(textRow) && textRow.intersects(tableArea)) {
                    // expand the table area to contain the rest of the text row
                    tableArea.setLeft((float)Math.floor(Math.min(textRow.getLeft(), tableArea.getLeft())));
                    tableArea.setRight((float)Math.ceil(Math.max(textRow.getRight(), tableArea.getRight())));
                }
            }
        }

        //this.debug(verticalTableRulings.keySet());

        // again look for text that intersects one of these rulings and extend the table accordingly
        /*
        for (Line textRow : lines) {
            for (Line2D.Float verticalRuling : verticalTableRulings.keySet()) {
                if (verticalRuling.intersects(textRow)) {
                    Rectangle tableArea = verticalTableRulings.get(verticalRuling);

                    if (!tableArea.contains(textRow)) {
                        tableArea.setLeft(Math.min(textRow.getLeft(), tableArea.getLeft()));
                        tableArea.setTop(Math.min(textRow.getTop(), tableArea.getTop()));
                        tableArea.setRight(Math.max(textRow.getRight(), tableArea.getRight()));
                        tableArea.setBottom(Math.max(textRow.getBottom(), tableArea.getBottom()));
                    }
                }
            }
        }

        // then use the text edges as a guide to what rows should be part of tables
        // first find edges that intersect existing table areas

        List<Line2D.Float> allTextEdges = new ArrayList<Line2D.Float>(leftTextEdges);
        allTextEdges.addAll(midTextEdges);
        allTextEdges.addAll(rightTextEdges);

        for (Line2D.Float edge : allTextEdges) {
            for (Rectangle tableArea : tableAreas) {
                if (!(tableArea.contains(edge.getP1()) && tableArea.contains(edge.getP2())) &&
                        edge.intersects(tableArea)) {

                    // ok this line intersects. now find all intersecting text lines that aren't in the table
                    // maybe they should be part of it!
                    // decide based on spacing of rows above and below and whether those rows are part of a table

                    float tableRowHeight = 0f;
                    float tableRowDistance = 0f;

                    Iterator<Line> iterator = lines.iterator();
                    Line prevRow = iterator.next();

                    while (prevRow != null && iterator.hasNext()) {
                        Line currRow = iterator.next();

                        if (tableArea.contains(currRow) && tableArea.contains(prevRow)) {
                            tableRowHeight = currRow.height;
                            tableRowDistance = currRow.getTop() - prevRow.getTop();
                        } else if (tableArea.contains(prevRow) && !tableArea.contains(currRow) &&
                                tableRowDistance > 0 && currRow.intersectsLine(edge)) {

                            float heightDiff = Math.abs(tableRowHeight - currRow.height);
                            float distanceDiff = Math.abs(tableRowDistance - (currRow.getTop() - prevRow.getTop()));

                            if (heightDiff <= 1.0 && distanceDiff <= 1.0) {
                                // let's extend the table down to include this row
                                tableArea.setBottom(currRow.getBottom());
                            }
                        }

                        prevRow = currRow;
                    }
                }
            }
        }
        */

        // before we return the table areas remove all duplicates
        Set<Rectangle> tableSet = new TreeSet<Rectangle>(new Comparator<Rectangle>() {
            @Override
            public int compare(Rectangle o1, Rectangle o2) {

                float overlap = o1.overlapRatio(o2);
                System.out.println("Overlap ratio of " + o1.toString() + " and " + o2.toString() + " is " + overlap);
                if (overlap >= 0.98) {
                    return 0;
                } else {
                    return 1;
                }
            }
        });

        tableSet.addAll(tableAreas);

        this.debug(tableSet);

        return new ArrayList<Rectangle>(tableSet);
    }

    private List<Line2D.Float> mergeHorizontalEdges(List<Line2D.Float> horizontalEdges) {
        if (horizontalEdges.size() == 0) {
            return horizontalEdges;
        }

        List<Line2D.Float> horizontalRulings = new ArrayList<Line2D.Float>();

        // sort rulings by top-leftmost first
        Collections.sort(horizontalEdges, new Comparator<Line2D.Float>() {
            @Override
            public int compare(Line2D.Float o1, Line2D.Float o2) {
                if (o1.equals(o2)) {
                    return 0;
                }

                if (o1.y1 == o2.y1) {
                    return Float.compare(o1.x1, o2.x1);
                } else {
                    return Float.compare(o1.y1, o2.y1);
                }
            }
        });

        Line2D.Float currentRuling = horizontalEdges.get(0);
        for (int i=1; i<horizontalEdges.size(); i++) {
            Line2D.Float nextEdge = horizontalEdges.get(i);

            if (currentRuling.y1 == nextEdge.y1 &&
                    nextEdge.x1 >= currentRuling.x1 &&
                    nextEdge.x1 <= currentRuling.x2) {
                // this line segment can be part of the current line
                currentRuling.x2 = Math.max(nextEdge.x2, currentRuling.x2);
            } else {
                // store the complete line and continue
                horizontalRulings.add(currentRuling);
                currentRuling = nextEdge;
            }
        }

        horizontalRulings.add(currentRuling);

        return horizontalRulings;
    }

    private List<Line2D.Float> mergeVerticalEdges(List<Line2D.Float> verticalEdges) {
        if (verticalEdges.size() == 0) {
            return verticalEdges;
        }

        List<Line2D.Float> verticalRulings = new ArrayList<Line2D.Float>();

        // sort by left-topmost first
        Collections.sort(verticalEdges, new Comparator<Line2D.Float>() {
            @Override
            public int compare(Line2D.Float o1, Line2D.Float o2) {
                if (o1.equals(o2)) {
                    return 0;
                }

                if (o1.x1 == o2.x1) {
                    return Float.compare(o1.y1, o2.y1);
                } else {
                    return Float.compare(o1.x1, o2.x1);
                }
            }
        });

        Line2D.Float currentRuling = verticalEdges.get(0);
        for (int i=1; i<verticalEdges.size(); i++) {
            Line2D.Float nextEdge = verticalEdges.get(i);

            if (currentRuling.x1 == nextEdge.x1 &&
                    nextEdge.y1 >= currentRuling.y1 &&
                    nextEdge.y1 <= currentRuling.y2) {
                // line segment is part of the current line
                currentRuling.y2 = Math.max(nextEdge.y2, currentRuling.y2);
            } else {
                // store the complete line and continue
                verticalRulings.add(currentRuling);
                currentRuling = nextEdge;
            }
        }

        verticalRulings.add(currentRuling);

        return verticalRulings;
    }

    private void debug(Collection<? extends Shape> shapes) {
        Color[] COLORS = { new Color(27, 158, 119),
                new Color(217, 95, 2), new Color(117, 112, 179),
                new Color(231, 41, 138), new Color(102, 166, 30) };

        Graphics2D g = (Graphics2D) debugImage.getGraphics();

        g.setStroke(new BasicStroke(2f));
        int i = 0;

        for (Shape s : shapes) {
            g.setColor(COLORS[(i++) % 5]);
            g.draw(s);
        }

        try {
            ImageIOUtil.writeImage(debugImage, debugFileOut, 72);
        } catch (IOException e) {
        }
    }

    private List<Rectangle> getTableAreasFromCells(List<Rectangle> cells) {
        List<List<Rectangle>> cellGroups = new ArrayList<List<Rectangle>>();
        for (Rectangle cell : cells) {
            boolean addedToGroup = false;

            cellCheck:
            for (List<Rectangle> cellGroup : cellGroups) {
                for (Rectangle groupCell : cellGroup) {
                    Point2D[] groupCellCorners = groupCell.getPoints();
                    Point2D[] candidateCorners = cell.getPoints();

                    for (int i=0; i<candidateCorners.length; i++) {
                        for (int j=0; j<groupCellCorners.length; j++) {
                            if (candidateCorners[i].distance(groupCellCorners[j]) < 10) {
                                cellGroup.add(cell);
                                addedToGroup = true;
                                break cellCheck;
                            }
                        }
                    }
                }
            }

            if (!addedToGroup) {
                ArrayList<Rectangle> cellGroup = new ArrayList<Rectangle>();
                cellGroup.add(cell);
                cellGroups.add(cellGroup);
            }
        }

        // create table areas based on cell group
        List<Rectangle> tableAreas = new ArrayList<Rectangle>();
        for (List<Rectangle> cellGroup : cellGroups) {
            // less than four cells should not make a table
            if (cellGroup.size() < 4) {
                continue;
            }

            float top = Float.MAX_VALUE;
            float left = Float.MAX_VALUE;
            float bottom = Float.MIN_VALUE;
            float right = Float.MIN_VALUE;

            for (Rectangle cell : cellGroup) {
                if (cell.getTop() < top) top = cell.getTop();
                if (cell.getLeft() < left) left = cell.getLeft();
                if (cell.getBottom() > bottom) bottom = cell.getBottom();
                if (cell.getRight() > right) right = cell.getRight();
            }

            tableAreas.add(new Rectangle(top, left, right - left, bottom - top));
        }

        return tableAreas;
    }

    private List<Rectangle> findRectangles(
            Set<Point2D.Float> crossingPoints,
            List<Line2D.Float> horizontalEdges,
            List<Line2D.Float> verticalEdges) {

        List<Rectangle> foundRectangles = new ArrayList<Rectangle>();

        ArrayList<Point2D.Float> sortedPoints = new ArrayList<Point2D.Float>(crossingPoints);

        // sort all points by y value and then x value - this means that the first element
        // is always the top-leftmost point
        Collections.sort(sortedPoints, pointComparator);

        for (int i=0; i<sortedPoints.size(); i++) {
            Point2D.Float topLeftPoint = sortedPoints.get(i);

            ArrayList<Point2D.Float> pointsBelow = new ArrayList<Point2D.Float>();
            ArrayList<Point2D.Float> pointsRight = new ArrayList<Point2D.Float>();

            for (int j=i+1; j<sortedPoints.size(); j++) {
                Point2D.Float checkPoint = sortedPoints.get(j);
                if (topLeftPoint.getX() == checkPoint.getX() && topLeftPoint.getY() < checkPoint.getY()) {
                    pointsBelow.add(checkPoint);
                } else if (topLeftPoint.getY() == checkPoint.getY() && topLeftPoint.getX() < checkPoint.getX()) {
                    pointsRight.add(checkPoint);
                }
            }

            nextCrossingPoint:
            for (Point2D.Float belowPoint : pointsBelow) {
                if (!this.edgeExistsBetween(topLeftPoint, belowPoint, verticalEdges)) {
                    break nextCrossingPoint;
                }

                for (Point2D.Float rightPoint : pointsRight) {
                    if (!this.edgeExistsBetween(topLeftPoint, rightPoint, horizontalEdges)) {
                        break nextCrossingPoint;
                    }

                    Point2D.Float bottomRightPoint = new Point2D.Float(rightPoint.x, belowPoint.y);

                    if (sortedPoints.contains(bottomRightPoint)
                            && this.edgeExistsBetween(belowPoint, bottomRightPoint, horizontalEdges)
                            && this.edgeExistsBetween(rightPoint, bottomRightPoint, verticalEdges)) {

                        foundRectangles.add(new Rectangle(
                                topLeftPoint.y,
                                topLeftPoint.x,
                                bottomRightPoint.x - topLeftPoint.x,
                                bottomRightPoint.y - topLeftPoint.y)
                        );

                        break nextCrossingPoint;
                    }
                }
            }
        }

        return foundRectangles;
    }

    private boolean edgeExistsBetween(Point2D.Float p1, Point2D.Float p2, List<Line2D.Float> edges) {
        for (Line2D.Float edge : edges) {
            if (p1.x >= edge.x1 && p1.x <= edge.x2 && p1.y >= edge.y1 && p1.y <= edge.y2
                    && p2.x >= edge.x1 && p2.x <= edge.x2 && p2.y >= edge.y1 && p2.y <= edge.y2) {
                return true;
            }
        }

        return false;
    }

    private Set<Point2D.Float> getCrossingPoints(List<Line2D.Float> horizontalEdges, List<Line2D.Float> verticalEdges) {
        Set<Point2D.Float> crossingPoints = new HashSet<Point2D.Float>();

        for (Line2D.Float horizontalEdge : horizontalEdges) {
            for (Line2D.Float verticalEdge : verticalEdges) {
                if (horizontalEdge.intersectsLine(verticalEdge)) {
                    crossingPoints.add(new Point2D.Float(verticalEdge.x1, horizontalEdge.y1));
                }
            }
        }

        return crossingPoints;
    }

    private List<Line2D.Float> getHorizontalRulings(BufferedImage image) {
        ArrayList<Line2D.Float> horizontalRulings = new ArrayList<Line2D.Float>();

        Raster r = image.getRaster();
        int width = r.getWidth();
        int height = r.getHeight();

        for (int x=0; x<width; x++) {

            int[] lastPixel = r.getPixel(x, 0, (int[])null);

            for (int y=1; y<height-1; y++) {

                int[] currPixel = r.getPixel(x, y, (int[])null);

                int diff = Math.abs(currPixel[0] - lastPixel[0]);
                if (diff > GRAYSCALE_INTENSITY_THRESHOLD) {
                    // we hit what could be a line
                    // don't bother scanning it if we've hit a pixel in the line before
                    boolean alreadyChecked = false;
                    for (Line2D.Float line : horizontalRulings) {
                        if (y == line.getY1() && x >= line.getX1() && x <= line.getX2()) {
                            alreadyChecked = true;
                            break;
                        }
                    }

                    if (alreadyChecked) {
                        lastPixel = currPixel;
                        continue;
                    }

                    int lineX = x + 1;

                    while (lineX < width) {
                        int[] linePixel = r.getPixel(lineX, y, (int[]) null);
                        int[] abovePixel = r.getPixel(lineX, y - 1, (int[]) null);

                        if (Math.abs(linePixel[0] - abovePixel[0]) <= GRAYSCALE_INTENSITY_THRESHOLD
                                || Math.abs(currPixel[0] - linePixel[0]) > GRAYSCALE_INTENSITY_THRESHOLD) {
                            break;
                        }

                        lineX++;
                    }

                    int endX = lineX - 1;
                    int lineWidth = endX - x;
                    if (lineWidth > 100) {
                        horizontalRulings.add(new Line2D.Float(x, y, endX, y));
                    }
                }

                lastPixel = currPixel;
            }
        }

        return horizontalRulings;
    }

    private List<Line2D.Float> getVerticalRulings(BufferedImage image) {
        ArrayList<Line2D.Float> verticalRulings = new ArrayList<Line2D.Float>();

        Raster r = image.getRaster();
        int width = r.getWidth();
        int height = r.getHeight();

        for (int y=0; y<height; y++) {

            int[] lastPixel = r.getPixel(0, y, (int[])null);

            for (int x=1; x<width-1; x++) {

                int[] currPixel = r.getPixel(x, y, (int[])null);

                int diff = Math.abs(currPixel[0] - lastPixel[0]);
                if (diff > GRAYSCALE_INTENSITY_THRESHOLD) {
                    // we hit what could be a line
                    // don't bother scanning it if we've hit a pixel in the line before
                    boolean alreadyChecked = false;
                    for (Line2D.Float line : verticalRulings) {
                        if (x == line.getX1() && y >= line.getY1() && y <= line.getY2()) {
                            alreadyChecked = true;
                            break;
                        }
                    }

                    if (alreadyChecked) {
                        lastPixel = currPixel;
                        continue;
                    }

                    int lineY = y + 1;

                    while (lineY < height) {
                        int[] linePixel = r.getPixel(x, lineY, (int[]) null);
                        int[] leftPixel = r.getPixel(x - 1, lineY, (int[]) null);

                        if (Math.abs(linePixel[0] - leftPixel[0]) <= GRAYSCALE_INTENSITY_THRESHOLD
                                || Math.abs(currPixel[0] - linePixel[0]) > GRAYSCALE_INTENSITY_THRESHOLD) {
                            break;
                        }

                        lineY++;
                    }

                    int endY = lineY - 1;
                    int lineLength = endY - y;
                    if (lineLength > 10) {
                        verticalRulings.add(new Line2D.Float(x, y, x, endY));
                    }
                }

                lastPixel = currPixel;
            }
        }

        return verticalRulings;
    }

    // taken from http://www.docjar.com/html/api/org/apache/pdfbox/examples/util/RemoveAllText.java.html
    private void removeText(PDDocument document, PDPage page) throws IOException {
        PDFStreamParser parser = new PDFStreamParser(page.getContents());
        parser.parse();

        List tokens = parser.getTokens();
        List newTokens = new ArrayList();

        for (int i=0; i<tokens.size(); i++) {
            Object token = tokens.get(i);
            if (token instanceof PDFOperator) {
                PDFOperator op = (PDFOperator)token;
                if (op.getOperation().equals("TJ") || op.getOperation().equals("Tj")) {
                    newTokens.remove(newTokens.size() - 1);
                    continue;
                }
            }
            newTokens.add(token);
        }

        PDStream newContents = new PDStream(document);
        ContentStreamWriter writer = new ContentStreamWriter(newContents.createOutputStream());
        writer.writeTokens(newTokens);
        newContents.addCompression();
        page.setContents(newContents);
    }
}
