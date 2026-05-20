package com.collinear.ycbench.plots;

import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.LogarithmicAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多模型对比网格 — {@code scripts/plot_comparison.py} 的 Java 移植版本。
 *
 * <p>扫描 DB 目录以匹配 {@code {config}_{seed}_{slug}.db} 格式的文件，
 * 渲染资金曲线网格（行=configs，列=seeds）。
 *
 * <p>用法：
 * <pre>
 *   java -cp ... com.collinear.ycbench.plots.PlotComparison [--db-dir db] [--out plots/comparison.png]
 * </pre>
 */
public final class PlotComparison {

    // ── Collinear 品牌调色板（来自 plot_comparison.py） ──
    private static final Color NAVY   = Color.decode("#13234D");
    private static final Color ORANGE = Color.decode("#F26125");
    private static final Color BLUE   = Color.decode("#4D65FF");
    private static final Color GREEN  = Color.decode("#22C55E");
    private static final Color BG     = Color.decode("#FAFBFD");
    private static final Color CARD   = Color.decode("#FFFFFF");
    private static final Color GRID   = Color.decode("#E8ECF2");
    private static final Color TEXT   = Color.decode("#2A2F3D");
    private static final Color MUTED  = Color.decode("#6B7694");

    private static final long INITIAL_FUNDS_CENTS = 25_000_000L;

    /** 已知模型定义 — 镜像 Python 中的 MODELS 字典。 */
    private static final Map<String, ModelDef> MODELS = new LinkedHashMap<>();
    static {
        MODELS.put("greedy_bot",        new ModelDef("Human Devised Rule", NAVY,   true));
        MODELS.put("throughput_bot",    new ModelDef("Throughput Bot",     MUTED,  true));
        MODELS.put("random_bot",        new ModelDef("Random Bot",         Color.GRAY, true));
        MODELS.put("prestige_bot",      new ModelDef("Prestige Bot",       ORANGE, true));
        // LLM models (extend as needed)
        MODELS.put("anthropic_claude-sonnet-4-6", new ModelDef("Sonnet 4.6", BLUE, false));
        MODELS.put("gemini_gemini-3-flash-preview", new ModelDef("Gemini 3 Flash", ORANGE, false));
        MODELS.put("openai_gpt-5.2",   new ModelDef("GPT-5.2", GREEN, false));
    }

    private static final String[] CONFIGS = {"default", "medium", "hard", "nightmare"};
    private static final int[] SEEDS = {1, 2, 3};

    static final class ModelDef {
        final String label;
        final Color color;
        final boolean isBot;
        ModelDef(String l, Color c, boolean b) { this.label = l; this.color = c; this.isBot = b; }
    }

    static final class RunCurve {
        final String config;
        final int seed;
        final String slug;
        final ModelDef model;
        final List<Date> times;
        final List<Double> balances;
        final boolean bankrupt;
        final double finalBalance;
        RunCurve(String c, int s, String sl, ModelDef m, List<Date> t, List<Double> b) {
            this.config = c; this.seed = s; this.slug = sl; this.model = m;
            this.times = t; this.balances = b;
            this.bankrupt = !b.isEmpty() && b.get(b.size() - 1) <= 0;
            this.finalBalance = b.isEmpty() ? 0 : b.get(b.size() - 1);
        }
    }

    private PlotComparison() { }

    public static void main(String[] args) throws Exception {
        String dbDir = "db";
        String outArg = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--db-dir": dbDir = args[++i]; break;
                case "--out":    outArg = args[++i]; break;
                default: System.err.println("Unknown: " + args[i]); System.exit(2);
            }
        }
        Path outPath = outArg != null ? Paths.get(outArg) : Paths.get("plots/comparison.png");

        // 发现运行记录
        List<RunCurve> allRuns = new ArrayList<>();
        Path dir = Paths.get(dbDir);
        if (!Files.isDirectory(dir)) { System.err.println("DB dir not found: " + dir); System.exit(1); }

        for (String config : CONFIGS) {
            for (int seed : SEEDS) {
                for (Map.Entry<String, ModelDef> entry : MODELS.entrySet()) {
                    String slug = entry.getKey();
                    Path dbFile = dir.resolve(config + "_" + seed + "_" + slug + ".db");
                    if (!Files.exists(dbFile)) continue;
                    List<Date> times = new ArrayList<>();
                    List<Double> balances = new ArrayList<>();
                    loadFundsCurve(dbFile, times, balances);
                    if (times.isEmpty()) continue;
                    RunCurve rc = new RunCurve(config, seed, slug, entry.getValue(), times, balances);
                    allRuns.add(rc);
                    String tag = rc.bankrupt ? "BANKRUPT" : String.format("$%,.0f", rc.finalBalance);
                    System.out.printf("  %s seed=%d %s: %s%n", config, seed, entry.getValue().label, tag);
                }
            }
        }
        if (allRuns.isEmpty()) { System.out.println("No runs found in " + dir); return; }

        // 确定网格维度
        List<String> usedConfigs = new ArrayList<>();
        List<Integer> usedSeeds = new ArrayList<>();
        for (RunCurve r : allRuns) { if (!usedConfigs.contains(r.config)) usedConfigs.add(r.config); }
        for (RunCurve r : allRuns) { if (!usedSeeds.contains(r.seed)) usedSeeds.add(r.seed); }
        int rows = usedConfigs.size();
        int cols = usedSeeds.size();

        int cellW = 700, cellH = 450;
        int headerH = 100;
        int imgW = cellW * cols, imgH = headerH + cellH * rows;
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(BG);
        g2.fillRect(0, 0, imgW, imgH);

        // 头部横幅
        g2.setColor(NAVY);
        g2.fillRect(0, 0, imgW, headerH - 6);
        g2.setColor(ORANGE);
        g2.fillRect(0, headerH - 6, imgW, 6);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("SansSerif", Font.BOLD, 36));
        String title = "YC-Bench  |  Multi-Model Comparison";
        int tw = g2.getFontMetrics().stringWidth(title);
        g2.drawString(title, (imgW - tw) / 2, headerH / 2 + 12);

        // 渲染每个单元格
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                String config = usedConfigs.get(row);
                int seed = usedSeeds.get(col);
                List<RunCurve> cell = new ArrayList<>();
                for (RunCurve r : allRuns) if (r.config.equals(config) && r.seed == seed) cell.add(r);
                JFreeChart chart = buildCellChart(cell, config, seed);
                BufferedImage cellImg = chart.createBufferedImage(cellW, cellH);
                g2.drawImage(cellImg, col * cellW, headerH + row * cellH, null);
            }
        }
        g2.dispose();

        Files.createDirectories(outPath.getParent() == null ? Paths.get(".") : outPath.getParent());
        ImageIO.write(img, "png", outPath.toFile());
        System.out.println("\nSaved: " + outPath);
    }

    private static JFreeChart buildCellChart(List<RunCurve> runs, String config, int seed) {
        TimeSeriesCollection ds = new TimeSeriesCollection();
        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(true, false);
        int idx = 0;
        for (RunCurve r : runs) {
            String label = r.bankrupt
                    ? r.model.label + " — bankrupt"
                    : String.format("%s — $%,.0f", r.model.label, r.finalBalance);
            TimeSeries s = new TimeSeries(label);
            for (int i = 0; i < r.times.size(); i++)
                s.addOrUpdate(new Millisecond(r.times.get(i)), r.balances.get(i));
            ds.addSeries(s);
            renderer.setSeriesPaint(idx, r.model.color);
            float lw = r.model.isBot ? 2.5f : 2.5f;
            float[] dash = r.model.isBot ? new float[]{6, 4} : null;
            float alpha = r.bankrupt ? 0.4f : 0.9f;
            BasicStroke stroke = dash != null
                    ? new BasicStroke(lw, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL, 1, dash, 0)
                    : new BasicStroke(lw);
            renderer.setSeriesStroke(idx, stroke);
            idx++;
        }

        NumberAxis yAxis = new NumberAxis("Balance ($)");
        yAxis.setAutoRangeIncludesZero(true);
        ChartTheme.applyMoneyAxis(yAxis);
        yAxis.setTickLabelPaint(TEXT);
        yAxis.setLabelPaint(MUTED);
        yAxis.setAxisLinePaint(GRID);

        DateAxis xAxis = new DateAxis();
        xAxis.setDateFormatOverride(new SimpleDateFormat("MMM ''yy"));
        xAxis.setTickLabelPaint(TEXT);
        xAxis.setAxisLinePaint(GRID);

        XYPlot plot = new XYPlot(ds, xAxis, yAxis, renderer);
        plot.setBackgroundPaint(CARD);
        plot.setDomainGridlinePaint(GRID);
        plot.setRangeGridlinePaint(GRID);
        plot.addRangeMarker(new ValueMarker(250000, MUTED,
                new BasicStroke(0.7f, 0, 0, 1, new float[]{3, 3}, 0)));

        String cellTitle = config + " seed=" + seed;
        JFreeChart chart = new JFreeChart(cellTitle, new Font("SansSerif", Font.BOLD, 12), plot, true);
        chart.setBackgroundPaint(BG);
        if (chart.getLegend() != null) {
            chart.getLegend().setBackgroundPaint(BG);
            chart.getLegend().setItemPaint(TEXT);
            chart.getLegend().setItemFont(new Font("SansSerif", Font.PLAIN, 9));
        }
        return chart;
    }

    private static void loadFundsCurve(Path dbFile, List<Date> times, List<Double> balances) throws Exception {
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT occurred_at, amount_cents FROM ledger_entries ORDER BY occurred_at ASC");
             ResultSet rs = ps.executeQuery()) {
            long running = INITIAL_FUNDS_CENTS;
            boolean first = true;
            while (rs.next()) {
                OffsetDateTime t = OffsetDateTime.parse(rs.getString(1));
                if (first) {
                    OffsetDateTime anchor = t.withMonth(1).withDayOfMonth(1).withHour(9)
                            .withMinute(0).withSecond(0).withNano(0);
                    times.add(Date.from(anchor.toInstant()));
                    balances.add(running / 100.0);
                    first = false;
                }
                running += rs.getLong(2);
                times.add(Date.from(t.toInstant()));
                balances.add(running / 100.0);
            }
        }
    }
}
