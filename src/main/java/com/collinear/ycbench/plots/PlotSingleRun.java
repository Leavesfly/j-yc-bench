package com.collinear.ycbench.plots;

import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

import java.awt.BasicStroke;
import java.awt.Color;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 单个基准 DB 的资金随时间变化曲线 — {@code scripts/plot_single_run.py} 的 Java 移植版本。
 *
 * <p>用法：
 * <pre>
 *   java -cp ... com.collinear.ycbench.plots.PlotSingleRun db/run.db [--out plots/run.png]
 * </pre>
 */
public final class PlotSingleRun {

    /** 镜像 Python 脚本中硬编码的起始余额。 */
    private static final long INITIAL_FUNDS_CENTS = 25_000_000L;

    private PlotSingleRun() { }

    public static void main(String[] args) throws Exception {
        String dbArg = null;
        String outArg = null;
        for (int i = 0; i < args.length; i++) {
            if ("--out".equals(args[i])) outArg = args[++i];
            else if (dbArg == null) dbArg = args[i];
            else { System.err.println("Unexpected arg: " + args[i]); System.exit(2); }
        }
        if (dbArg == null) {
            System.err.println("Usage: PlotSingleRun <db-path> [--out plots/run.png]");
            System.exit(2);
        }
        Path dbPath = Paths.get(dbArg);
        Path outPath = outArg != null
                ? Paths.get(outArg)
                : Paths.get("plots").resolve(stripExt(dbPath.getFileName().toString()) + ".png");

        List<long[]> rows = loadLedger(dbPath);
        if (rows.isEmpty()) {
            System.out.println("No ledger entries found.");
            return;
        }

        TimeSeries series = new TimeSeries("balance");
        OffsetDateTime first = OffsetDateTime.parse(epochToIso(rows.get(0)[0]));
        // 镜像 Python：将第一个样本锚定在同一年的一月一日 09:00。
        OffsetDateTime anchor = first.withMonth(1).withDayOfMonth(1).withHour(9)
                .withMinute(0).withSecond(0).withNano(0);
        long running = INITIAL_FUNDS_CENTS;
        series.addOrUpdate(new Millisecond(new Date(anchor.toInstant().toEpochMilli())), running / 100.0);
        for (long[] r : rows) {
            running += r[1];
            series.addOrUpdate(new Millisecond(new Date(r[0])), running / 100.0);
        }

        JFreeChart chart = buildChart(series, stripExt(dbPath.getFileName().toString()), running / 100.0);
        Files.createDirectories(outPath.getParent() == null ? Paths.get(".") : outPath.getParent());
        ChartUtils.saveChartAsPNG(outPath.toFile(), chart, 1800, 750);
        System.out.println("Saved: " + outPath);
    }

    /** 返回按升序排序的 {@code [occurredAtEpochMillis, amountCents]} 行列表。 */
    private static List<long[]> loadLedger(Path dbPath) throws Exception {
        List<long[]> rows = new ArrayList<>();
        String url = "jdbc:sqlite:" + dbPath.toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT occurred_at, amount_cents FROM ledger_entries ORDER BY occurred_at ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                OffsetDateTime t = OffsetDateTime.parse(rs.getString(1));
                rows.add(new long[]{t.toInstant().toEpochMilli(), rs.getLong(2)});
            }
        }
        return rows;
    }

    private static JFreeChart buildChart(TimeSeries series, String dbStem, double finalBalance) {
        TimeSeriesCollection ds = new TimeSeriesCollection(series);
        String title = String.format("%s — final: $%,.0f", dbStem, finalBalance);
        JFreeChart chart = org.jfree.chart.ChartFactory.createTimeSeriesChart(
                title, null, "Balance (USD)", ds, false, false, false);
        ChartTheme.apply(chart);

        XYPlot plot = chart.getXYPlot();
        XYLineAndShapeRenderer r = new XYLineAndShapeRenderer(true, false);
        r.setSeriesPaint(0, ChartTheme.ACCENT);
        r.setSeriesStroke(0, new BasicStroke(2.0f));
        plot.setRenderer(r);

        DateAxis xa = (DateAxis) plot.getDomainAxis();
        xa.setDateFormatOverride(new SimpleDateFormat("MMM ''yy"));

        NumberAxis ya = (NumberAxis) plot.getRangeAxis();
        ChartTheme.applyMoneyAxis(ya);
        ya.setAutoRangeIncludesZero(true);

        // 破产线 (y=0) + 资本参考线 (y=250K)。
        plot.addRangeMarker(makeMarker(0, ChartTheme.WARN, new float[]{6f, 4f}));
        plot.addRangeMarker(makeMarker(250_000, ChartTheme.MUTED_DASH, new float[]{2f, 4f}));
        return chart;
    }

    private static org.jfree.chart.plot.ValueMarker makeMarker(double y, Color c, float[] dash) {
        org.jfree.chart.plot.ValueMarker m = new org.jfree.chart.plot.ValueMarker(y);
        m.setPaint(c);
        m.setStroke(new BasicStroke(0.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 1f, dash, 0));
        return m;
    }

    private static String stripExt(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? name : name.substring(0, i);
    }

    private static String epochToIso(long epochMillis) {
        return new Date(epochMillis).toInstant().toString();
    }
}
