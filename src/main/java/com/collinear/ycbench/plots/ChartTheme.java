package com.collinear.ycbench.plots;

import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.axis.ValueAxis;
import org.jfree.chart.plot.CategoryPlot;
import org.jfree.chart.plot.Plot;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.title.TextTitle;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.text.DecimalFormat;
import java.text.NumberFormat;

/**
 * JFreeChart 图表的共享暗色主题样式 — 镜像 {@code scripts/plot_*.py} 中的 matplotlib 外观
 * （背景色 #0f1117，前景色 #1a1d27，线条色 #4fc3f7）。集中在此处以确保所有图表外观一致。
 */
public final class ChartTheme {

    public static final Color BG          = Color.decode("#0f1117");
    public static final Color FACE        = Color.decode("#1a1d27");
    public static final Color GRID        = Color.decode("#333344");
    public static final Color TEXT        = Color.decode("#aaaaaa");
    public static final Color TEXT_BRIGHT = Color.decode("#ffffff");
    public static final Color ACCENT      = Color.decode("#4fc3f7");
    public static final Color WARN        = Color.decode("#e74c3c");
    public static final Color MUTED_DASH  = Color.decode("#555577");

    /** 领域颜色调色板 — 与 plot_run.py 中的 {@code DOMAIN_COLORS} 匹配。 */
    public static final Color RESEARCH         = Color.decode("#3498db");
    public static final Color INFERENCE        = Color.decode("#9b59b6");
    public static final Color DATA_ENVIRONMENT = Color.decode("#1abc9c");
    public static final Color TRAINING         = Color.decode("#e67e22");

    /** 多轮次覆盖的系列调色板（与 plot_run.py 中的 {@code COLORS} 匹配）。 */
    public static final Color[] SERIES = {
            Color.decode("#00d4aa"), Color.decode("#ff6b6b"), Color.decode("#4ecdc4"),
            Color.decode("#ffe66d"), Color.decode("#a29bfe"), Color.decode("#fd79a8"),
            Color.decode("#6c5ce7"), Color.decode("#00b894")
    };

    private ChartTheme() { }

    public static Color seriesColor(int i) {
        return SERIES[Math.floorMod(i, SERIES.length)];
    }

    public static Color domainColor(String domain) {
        switch (domain) {
            case "research":         return RESEARCH;
            case "inference":        return INFERENCE;
            case "data_environment": return DATA_ENVIRONMENT;
            case "training":         return TRAINING;
            default:                 return Color.GRAY;
        }
    }

    /** 将暗色主题应用于基于 XYPlot 的图表。 */
    public static void apply(JFreeChart chart) {
        chart.setBackgroundPaint(BG);
        TextTitle t = chart.getTitle();
        if (t != null) {
            t.setPaint(TEXT_BRIGHT);
            t.setFont(new Font("SansSerif", Font.BOLD, 13));
        }
        Plot plot = chart.getPlot();
        plot.setBackgroundPaint(FACE);
        plot.setOutlinePaint(GRID);
        if (plot instanceof XYPlot) {
            XYPlot xy = (XYPlot) plot;
            xy.setDomainGridlinePaint(GRID);
            xy.setRangeGridlinePaint(GRID);
            xy.setDomainGridlineStroke(new BasicStroke(0.5f));
            xy.setRangeGridlineStroke(new BasicStroke(0.5f));
            styleAxis(xy.getDomainAxis());
            styleAxis(xy.getRangeAxis());
        } else if (plot instanceof CategoryPlot) {
            CategoryPlot cp = (CategoryPlot) plot;
            cp.setDomainGridlinePaint(GRID);
            cp.setRangeGridlinePaint(GRID);
            styleAxis(cp.getDomainAxis());
            styleAxis(cp.getRangeAxis());
        }
        if (chart.getLegend() != null) {
            chart.getLegend().setBackgroundPaint(FACE);
            chart.getLegend().setItemPaint(TEXT);
            chart.getLegend().setItemFont(new Font("SansSerif", Font.PLAIN, 9));
        }
    }

    public static void styleAxis(ValueAxis axis) {
        if (axis == null) return;
        Font tickFont  = new Font("SansSerif", Font.PLAIN, 9);
        Font labelFont = new Font("SansSerif", Font.PLAIN, 10);
        axis.setTickLabelPaint(TEXT);
        axis.setLabelPaint(TEXT);
        axis.setAxisLinePaint(GRID);
        axis.setTickMarkPaint(GRID);
        axis.setTickLabelFont(tickFont);
        axis.setLabelFont(labelFont);
    }

    public static void styleAxis(org.jfree.chart.axis.CategoryAxis axis) {
        if (axis == null) return;
        axis.setTickLabelPaint(TEXT);
        axis.setLabelPaint(TEXT);
        axis.setAxisLinePaint(GRID);
        axis.setTickMarkPaint(GRID);
        axis.setTickLabelFont(new Font("SansSerif", Font.PLAIN, 9));
        axis.setLabelFont(new Font("SansSerif", Font.PLAIN, 10));
    }

    /** 用于货币 Y 轴的 {@code $1,234K / $1.2M} 格式化器。 */
    public static NumberFormat moneyFormat() {
        return new DecimalFormat() {
            @Override
            public StringBuffer format(double v, StringBuffer toAppendTo, java.text.FieldPosition pos) {
                double abs = Math.abs(v);
                String s;
                if (abs >= 1_000_000) s = String.format("$%.1fM", v / 1_000_000.0);
                else if (abs >= 1_000) s = String.format("$%.0fK", v / 1_000.0);
                else                   s = String.format("$%.0f", v);
                return toAppendTo.append(s);
            }
            @Override
            public StringBuffer format(long v, StringBuffer toAppendTo, java.text.FieldPosition pos) {
                return format((double) v, toAppendTo, pos);
            }
            @Override
            public Number parse(String src, java.text.ParsePosition p) { return null; }
        };
    }

    /** 在指定的数值范围轴上应用货币格式化器。 */
    public static void applyMoneyAxis(NumberAxis axis) {
        axis.setNumberFormatOverride(moneyFormat());
    }
}
