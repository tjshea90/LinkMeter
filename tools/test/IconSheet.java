package com.tj.linkmeter;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

/** Draws every icon with Java2D into one picture, so the geometry in Icons can be looked at. Not part of the app. */
public final class IconSheet {
    private IconSheet() {}

    public static void main(String[] a) throws IOException {
        Icons.Kind[] kinds = Icons.Kind.values();
        int cols = 5;
        int cell = 150;
        int rows = (kinds.length + cols - 1) / cols;
        BufferedImage img = new BufferedImage(cols * cell, rows * cell + 40, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(new Color(0x101216));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.setFont(new Font("SansSerif", Font.PLAIN, 14));
        for (int i = 0; i < kinds.length; i++) {
            int x = (i % cols) * cell;
            int y = (i / cols) * cell;
            g.setColor(new Color(0x1B1E24));
            g.fillRoundRect(x + 8, y + 8, cell - 16, cell - 16, 18, 18);
            // the icon the way a bar button draws it: 56 dp button, icon radius about 10 dp, scaled x2.6 here
            float r = 28f;
            final Graphics2D gg = g;
            final float stroke = r * 0.16f;
            Icons.Pen pen = new Icons.Pen() {
                @Override
                public void line(float x1, float y1, float x2, float y2) {
                    gg.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    gg.draw(new Line2D.Float(x1, y1, x2, y2));
                }

                @Override
                public void circle(float cx, float cy, float rr) {
                    gg.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    gg.draw(new Ellipse2D.Float(cx - rr, cy - rr, rr * 2, rr * 2));
                }

                @Override
                public void dot(float cx, float cy, float rr) {
                    gg.fill(new Ellipse2D.Float(cx - rr, cy - rr, rr * 2, rr * 2));
                }

                @Override
                public void rrect(float l, float t, float rt, float b, float rx) {
                    gg.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    gg.draw(new RoundRectangle2D.Float(l, t, rt - l, b - t, rx * 2, rx * 2));
                }

                @Override
                public void arc(float l, float t, float rt, float b, float start, float sweep) {
                    gg.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    // Java2D measures angles counter-clockwise: flip them to the clockwise convention
                    gg.draw(new Arc2D.Float(l, t, rt - l, b - t, -start, -sweep, Arc2D.OPEN));
                }

                @Override
                public void poly(float[] xy, boolean closed, boolean filled) {
                    Path2D.Float path = new Path2D.Float();
                    path.moveTo(xy[0], xy[1]);
                    for (int j = 2; j < xy.length; j += 2) {
                        path.lineTo(xy[j], xy[j + 1]);
                    }
                    if (closed) {
                        path.closePath();
                    }
                    if (filled) {
                        gg.fill(path);
                    } else {
                        gg.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                        gg.draw(path);
                    }
                }
            };
            g.setColor(new Color(0xF2F4F7));
            Icons.draw(kinds[i], pen, x + cell / 2f, y + cell / 2f - 8, r);
            g.setColor(new Color(0x9AA0A9));
            String name = kinds[i].name();
            g.drawString(name, x + cell / 2 - g.getFontMetrics().stringWidth(name) / 2, y + cell - 16);
        }
        g.dispose();
        ImageIO.write(img, "png", new File(a[0]));
        System.out.println("wrote " + a[0]);
    }
}
