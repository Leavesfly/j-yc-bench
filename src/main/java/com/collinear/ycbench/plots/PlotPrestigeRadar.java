package com.collinear.ycbench.plots;

import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.SpiderWebPlot;
import org.jfree.chart.title.LegendTitle;
import org.jfree.chart.title.TextTitle;
import org.jfree.data.category.DefaultCategoryDataset;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 声望雷达图 — {@code scripts/plot_prestige_radar.py} 的 Java 移植版本。
 *
 * <p>从每个 DB 读取最终各领域声望值，渲染蜘蛛/雷达图网格。
 *
 * <p>用法：
 * <pre>
 *   java -cp ... com.collinear.ycbench.plots.PlotPrestigeRadar [--db-dir db] [--out plots/radar.png]
 * </pre>
 */
public final class PlotPrestigeRadar {

    private static final Color NAVY   = Color.decode("#13234D");
    private static final Color ORANGE = Color.decode("#F26125");
    private static final Color BLUE   = Color.decode("#4D65FF");
    private static final Color GREEN  = Color.decode("#22C55E");
    private static final Color BG     = Color.decode("#FAFBFD");
    private static final Color CARD   = Color.decode("#FFFFFF");
    private static final Color TEXT   = Color.decode("#2A2F3D");

    private static final String[] DOMAINS = {"research", "inference", "data_environment", "training"};
    private static final String[] DOMAIN_LABELS = {"RES", "INF", "DATA/ENV", "TRAIN"};

    private static final String[] CONFIGS = {"default", "medium", "hard", "nightmare"};
    private static final int[] SEEDS = {1, 2, 3};

    private static final Map<String, ModelDef> MODELS = new LinkedHashMap<>();
    static {
        MODELS.put("greedy_bot",     new ModelDef("Greedy", NAVY));
        MODELS.put("throughput_bot", new ModelDef("Throughput", Color.decode("#6B7694")));
        MODELS.put("random_bot",     new ModelDef("Random", Color.GRAY));
        MODELS.put("prestige_bot",   new ModelDef("Prestige", ORANGE));
    }

    static final class ModelDef {
        final String label; final Color color;
        ModelDef(String l, Color c) { this.label = l; this.color = c; }
    }

    static final class RunPrestige {
        final String config; final int seed; final String slug;
        final ModelDef model; final double[] values;
        RunPrestige(String c, int s, String sl, ModelDef m, double[] v) {
            this.config = c; this.seed = s; this.slug = sl; this.model = m; this.values = v;
        }
    }

    private PlotPrestigeRadar() { }

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
        Path outPath = outArg != null ? Paths.get(outArg) : Paths.get("plots/prestige_radar.png");
        Path dir = Paths.get(dbDir);

        List<RunPrestige> allRuns = new ArrayList<>();
        for (String config : CONFIGS) {
            for (int seed : SEEDS) {
                for (Map.Entry<String, ModelDef> entry : MODELS.entrySet()) {
                    Path dbFile = dir.resolve(config + "_" + seed + "_" + entry.getKey() + ".db");
                    if (!Files.exists(dbFile)) continue;
                    double[] vals = loadPrestige(dbFile);
                    if (vals == null) continue;
                    allRuns.add(new RunPrestige(config, seed, entry.getKey(), entry.getValue(), vals));
                    System.out.printf("  %s seed=%d %s: max=%.1f%n",
                            config, seed, entry.getValue().label, max(vals));
                }
            }
        }
        if (allRuns.isEmpty()) { System.out.println("No runs found."); return; }

        // 确定网格
        List<String> usedConfigs = new ArrayList<>();
        List<Integer> usedSeeds = new ArrayList<>();
        for (RunPrestige r : allRuns) { if (!usedConfigs.contains(r.config)) usedConfigs.add(r.config); }
        for (RunPrestige r : allRuns) { if (!usedSeeds.contains(r.seed)) usedSeeds.add(r.seed); }
        int rows = usedConfigs.size(), cols = usedSeeds.size();

        int cellW = 500, cellH = 500, headerH = 80;
        int imgW = cellW * cols, imgH = headerH + cellH * rows;
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(BG);
        g2.fillRect(0, 0, imgW, imgH);
        g2.setColor(NAVY);
        g2.fillRect(0, 0, imgW, headerH - 5);
        g2.setColor(ORANGE);
        g2.fillRect(0, headerH - 5, imgW, 5);
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("SansSerif", Font.BOLD, 28));
        String title = "YC-Bench  |  Prestige Radar";
        int tw = g2.getFontMetrics().stringWidth(title);
        g2.drawString(title, (imgW - tw) / 2, headerH / 2 + 10);

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                String config = usedConfigs.get(row);
                int seed = usedSeeds.get(col);
                List<RunPrestige> cell = new ArrayList<>();
                for (RunPrestige r : allRuns) if (r.config.equals(config) && r.seed == seed) cell.add(r);
                if (cell.isEmpty()) continue;
                JFreeChart ch = buildRadarChart(cell, config, seed);
                BufferedImage cellImg = ch.createBufferedImage(cellW, cellH);
                g2.drawImage(cellImg, col * cellW, headerH + row * cellH, null);
            }
        }
        g2.dispose();
        Files.createDirectories(outPath.getParent() == null ? Paths.get(".") : outPath.getParent());
        ImageIO.write(img, "png", outPath.toFile());
        System.out.println("\nSaved: " + outPath);
    }

    private static JFreeChart buildRadarChart(List<RunPrestige> runs, String config, int seed) {
        DefaultCategoryDataset ds = new DefaultCategoryDataset();
        for (RunPrestige r : runs) {
            for (int i = 0; i < DOMAINS.length; i++) {
                ds.addValue(r.values[i], r.model.label, DOMAIN_LABELS[i]);
            }
        }
        SpiderWebPlot plot = new SpiderWebPlot(ds);
        plot.setMaxValue(10.0);
        plot.setBackgroundPaint(CARD);
        plot.setOutlinePaint(Color.decode("#E8ECF2"));
        plot.setWebFilled(true);
        for (int i = 0; i < runs.size(); i++) {
            plot.setSeriesPaint(i, runs.get(i).model.color);
            plot.setSeriesOutlineStroke(i, new BasicStroke(2.0f));
        }
        plot.setLabelFont(new Font("SansSerif", Font.PLAIN, 10));
        plot.setLabelPaint(TEXT);

        JFreeChart chart = new JFreeChart(config + " seed=" + seed,
                new Font("SansSerif", Font.BOLD, 11), plot, true);
        chart.setBackgroundPaint(BG);
        TextTitle tt = chart.getTitle();
        if (tt != null) tt.setPaint(TEXT);
        LegendTitle leg = chart.getLegend();
        if (leg != null) {
            leg.setBackgroundPaint(BG);
            leg.setItemPaint(TEXT);
            leg.setItemFont(new Font("SansSerif", Font.PLAIN, 9));
        }
        return chart;
    }

    private static double[] loadPrestige(Path dbFile) throws Exception {
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();
        try (Connection c = DriverManager.getConnection(url);
             PreparedStatement ps = c.prepareStatement(
                     "SELECT domain, prestige_level FROM company_prestige");
             ResultSet rs = ps.executeQuery()) {
            Map<String, Double> map = new LinkedHashMap<>();
            while (rs.next()) map.put(rs.getString(1), rs.getDouble(2));
            if (map.isEmpty()) return null;
            double[] vals = new double[DOMAINS.length];
            for (int i = 0; i < DOMAINS.length; i++) vals[i] = map.getOrDefault(DOMAINS[i], 1.0);
            return vals;
        }
    }

    private static double max(double[] arr) {
        double m = arr[0];
        for (double v : arr) if (v > m) m = v;
        return m;
    }
}
