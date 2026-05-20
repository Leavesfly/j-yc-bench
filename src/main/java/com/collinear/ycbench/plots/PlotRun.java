package com.collinear.ycbench.plots;

import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.DateAxis;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.plot.CombinedDomainXYPlot;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.renderer.xy.XYStepRenderer;
import org.jfree.data.time.Millisecond;
import org.jfree.data.time.TimeSeries;
import org.jfree.data.time.TimeSeriesCollection;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

/**
 * 从结果 JSON 生成的 8 子图仪表板 — {@code scripts/plot_run.py} 的 Java 移植版本。
 *
 * <p>绘制：资金、任务累计、声望、信任度、工资单、分配情况、Token 数、成本。
 *
 * <p>用法：
 * <pre>
 *   java -cp ... com.collinear.ycbench.plots.PlotRun results/*.json [--out plots/run_analysis.png]
 * </pre>
 */
public final class PlotRun {

    private PlotRun() { }

    public static void main(String[] args) throws Exception {
        List<String> paths = new ArrayList<>();
        String outArg = null;
        for (int i = 0; i < args.length; i++) {
            if ("--out".equals(args[i])) { outArg = args[++i]; continue; }
            paths.add(args[i]);
        }
        if (paths.isEmpty()) {
            System.err.println("Usage: PlotRun results/*.json [--out plots/run_analysis.png]");
            System.exit(2);
        }
        Path outPath = outArg != null ? Paths.get(outArg) : Paths.get("plots/run_analysis.png");

        List<ResultData> runs = new ArrayList<>();
        for (String p : paths) runs.add(ResultData.load(p));

        // 打印每个运行的摘要
        for (ResultData r : runs) printSummary(r);

        // 构建 8 个图表（4 行 × 2 列）
        JFreeChart[][] charts = new JFreeChart[4][2];
        charts[0][0] = plotFunds(runs);
        charts[0][1] = plotTasksCumulative(runs);
        charts[1][0] = plotPrestige(runs);
        charts[1][1] = plotTrust(runs);
        charts[2][0] = plotPayroll(runs);
        charts[2][1] = plotAssignments(runs);
        charts[3][0] = plotTokens(runs);
        charts[3][1] = plotCost(runs);

        // 渲染为单个复合 PNG（4×2 网格）
        int cellW = 900, cellH = 400;
        int imgW = cellW * 2, imgH = cellH * 4;
        BufferedImage img = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setColor(ChartTheme.BG);
        g2.fillRect(0, 0, imgW, imgH);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 2; col++) {
                JFreeChart ch = charts[row][col];
                if (ch == null) continue;
                BufferedImage cell = ch.createBufferedImage(cellW, cellH);
                g2.drawImage(cell, col * cellW, row * cellH, null);
            }
        }
        g2.dispose();

        Files.createDirectories(outPath.getParent() == null ? Paths.get(".") : outPath.getParent());
        ImageIO.write(img, "png", outPath.toFile());
        System.out.println("\nPlot saved to " + outPath);
    }

    // ─── 子图构建器 ───────────────────────────────────────────────────

    private static JFreeChart plotFunds(List<ResultData> runs) {
        TimeSeriesCollection ds = new TimeSeriesCollection();
        for (int i = 0; i < runs.size(); i++) {
            ResultData r = runs.get(i);
            if (r.funds.isEmpty()) continue;
            TimeSeries s = new TimeSeries(r.shortName());
            for (ResultData.FundsPoint p : r.funds)
                s.addOrUpdate(new Millisecond(p.time), p.fundsCents / 100.0);
            ds.addSeries(s);
        }
        JFreeChart ch = org.jfree.chart.ChartFactory.createTimeSeriesChart(
                "Funds Over Time", null, "Funds ($)", ds, true, false, false);
        ChartTheme.apply(ch);
        applySeriesColors(ch.getXYPlot(), runs.size());
        ChartTheme.applyMoneyAxis((NumberAxis) ch.getXYPlot().getRangeAxis());
        ch.getXYPlot().addRangeMarker(new ValueMarker(200000, new Color(128,128,128,60),
                new BasicStroke(0.7f, 0, 0, 1, new float[]{4,4}, 0)));
        return ch;
    }

    private static JFreeChart plotTasksCumulative(List<ResultData> runs) {
        TimeSeriesCollection ds = new TimeSeriesCollection();
        for (int i = 0; i < runs.size(); i++) {
            ResultData r = runs.get(i);
            List<ResultData.TaskPoint> ok = r.tasks.stream()
                    .filter(t -> t.success)
                    .sorted(Comparator.comparing(t -> t.completedAt))
                    .collect(Collectors.toList());
            if (ok.isEmpty()) continue;
            TimeSeries s = new TimeSeries(r.shortName() + " OK");
            int cum = 0;
            for (ResultData.TaskPoint t : ok) s.addOrUpdate(new Millisecond(t.completedAt), ++cum);
            ds.addSeries(s);
        }
        JFreeChart ch = org.jfree.chart.ChartFactory.createTimeSeriesChart(
                "Task Completions (OK)", null, "Cumulative Tasks", ds, true, false, false);
        ChartTheme.apply(ch);
        applySeriesColors(ch.getXYPlot(), runs.size());
        return ch;
    }

    private static JFreeChart plotPrestige(List<ResultData> runs) {
        TimeSeriesCollection ds = new TimeSeriesCollection();
        if (!runs.isEmpty()) {
            ResultData r = runs.get(0);
            Set<String> domains = new LinkedHashSet<>();
            for (ResultData.PrestigePoint p : r.prestige) domains.add(p.domain);
            for (String dom : domains) {
                TimeSeries s = new TimeSeries(dom);
                for (ResultData.PrestigePoint p : r.prestige)
                    if (p.domain.equals(dom)) s.addOrUpdate(new Millisecond(p.time), p.level);
                ds.addSeries(s);
            }
        }
        JFreeChart ch = org.jfree.chart.ChartFactory.createTimeSeriesChart(
                "Prestige by Domain", null, "Prestige Level", ds, true, false, false);
        ChartTheme.apply(ch);
        XYPlot plot = ch.getXYPlot();
        XYLineAndShapeRenderer rr = new XYLineAndShapeRenderer(true, false);
        if (!runs.isEmpty()) {
            Set<String> domains = new LinkedHashSet<>();
            for (ResultData.PrestigePoint p : runs.get(0).prestige) domains.add(p.domain);
            int idx = 0;
            for (String d : domains) { rr.setSeriesPaint(idx, ChartTheme.domainColor(d)); idx++; }
        }
        plot.setRenderer(rr);
        return ch;
    }

    private static JFreeChart plotTrust(List<ResultData> runs) {
        TimeSeriesCollection ds = new TimeSeriesCollection();
        if (!runs.isEmpty()) {
            ResultData r = runs.get(0);
            Set<String> clients = new LinkedHashSet<>();
            for (ResultData.TrustPoint t : r.clientTrust) clients.add(t.clientName);
            for (String cn : clients) {
                TimeSeries s = new TimeSeries(cn);
                for (ResultData.TrustPoint t : r.clientTrust)
                    if (t.clientName.equals(cn)) s.addOrUpdate(new Millisecond(t.time), t.trustLevel);
                ds.addSeries(s);
            }
        }
        JFreeChart ch = org.jfree.chart.ChartFactory.createTimeSeriesChart(
                "Client Trust", null, "Trust Level", ds, true, false, false);
        ChartTheme.apply(ch);
        applySeriesColors(ch.getXYPlot(), ds.getSeriesCount());
        return ch;
    }

    private static JFreeChart plotPayroll(List<ResultData> runs) {
        TimeSeriesCollection ds = new TimeSeriesCollection();
        for (int i = 0; i < runs.size(); i++) {
            ResultData r = runs.get(i);
            // 按月份分组 monthly_payroll
            Map<String, Long> monthly = new LinkedHashMap<>();
            for (ResultData.LedgerPoint lp : r.ledger) {
                if (!"monthly_payroll".equals(lp.category)) continue;
                String ym = new SimpleDateFormat("yyyy-MM").format(lp.time);
                monthly.merge(ym, Math.abs(lp.amountCents), Long::sum);
            }
            if (monthly.isEmpty()) continue;
            TimeSeries s = new TimeSeries(r.shortName());
            for (Map.Entry<String, Long> e : monthly.entrySet()) {
                try {
                    Date d = new SimpleDateFormat("yyyy-MM").parse(e.getKey());
                    s.addOrUpdate(new Millisecond(d), e.getValue() / 100.0);
                } catch (Exception ignored) { }
            }
            ds.addSeries(s);
        }
        JFreeChart ch = org.jfree.chart.ChartFactory.createTimeSeriesChart(
                "Payroll Growth", null, "Monthly Payroll ($)", ds, true, false, false);
        ChartTheme.apply(ch);
        applySeriesColors(ch.getXYPlot(), runs.size());
        ChartTheme.applyMoneyAxis((NumberAxis) ch.getXYPlot().getRangeAxis());
        return ch;
    }

    private static JFreeChart plotAssignments(List<ResultData> runs) {
        TimeSeriesCollection ds = new TimeSeriesCollection();
        for (int i = 0; i < runs.size(); i++) {
            ResultData r = runs.get(i);
            if (r.assignments.isEmpty()) continue;
            TimeSeries s = new TimeSeries(r.shortName());
            for (ResultData.AssignmentPoint a : r.assignments)
                s.addOrUpdate(new Millisecond(a.completedAt), a.numAssigned);
            ds.addSeries(s);
        }
        JFreeChart ch = org.jfree.chart.ChartFactory.createTimeSeriesChart(
                "Assignment Pattern Per Task", null, "Employees Assigned", ds, true, false, false);
        ChartTheme.apply(ch);
        // 散点样式：显示形状，无线条
        XYLineAndShapeRenderer rr = new XYLineAndShapeRenderer(false, true);
        for (int i = 0; i < runs.size(); i++) {
            rr.setSeriesPaint(i, ChartTheme.seriesColor(i));
            rr.setSeriesShapesVisible(i, true);
        }
        ch.getXYPlot().setRenderer(rr);
        ch.getXYPlot().addRangeMarker(new ValueMarker(4, new Color(0,180,0,80),
                new BasicStroke(0.7f, 0, 0, 1, new float[]{4,4}, 0)));
        return ch;
    }

    private static JFreeChart plotTokens(List<ResultData> runs) {
        XYSeriesCollection ds = new XYSeriesCollection();
        for (int i = 0; i < runs.size(); i++) {
            ResultData r = runs.get(i);
            if (r.transcript.isEmpty()) continue;
            XYSeries s = new XYSeries(r.shortName() + " prompt");
            for (ResultData.TranscriptPoint tp : r.transcript) s.add(tp.turn, tp.promptTokens);
            ds.addSeries(s);
        }
        JFreeChart ch = org.jfree.chart.ChartFactory.createXYLineChart(
                "Prompt Tokens Per Turn", "Turn", "Tokens", ds);
        ChartTheme.apply(ch);
        applySeriesColors(ch.getXYPlot(), runs.size());
        return ch;
    }

    private static JFreeChart plotCost(List<ResultData> runs) {
        XYSeriesCollection ds = new XYSeriesCollection();
        for (int i = 0; i < runs.size(); i++) {
            ResultData r = runs.get(i);
            if (r.transcript.isEmpty()) continue;
            XYSeries s = new XYSeries(r.shortName());
            double cum = 0;
            for (ResultData.TranscriptPoint tp : r.transcript) { cum += tp.costUsd; s.add(tp.turn, cum); }
            ds.addSeries(s);
        }
        JFreeChart ch = org.jfree.chart.ChartFactory.createXYLineChart(
                "API Cost", "Turn", "Cumulative Cost ($)", ds);
        ChartTheme.apply(ch);
        applySeriesColors(ch.getXYPlot(), runs.size());
        return ch;
    }

    // ─── 辅助方法 ────────────────────────────────────────────────────────────

    private static void applySeriesColors(XYPlot plot, int count) {
        XYItemRenderer r = plot.getRenderer();
        for (int i = 0; i < count; i++) {
            r.setSeriesPaint(i, ChartTheme.seriesColor(i));
            r.setSeriesStroke(i, new BasicStroke(2.0f));
        }
    }

    private static void printSummary(ResultData r) {
        long revenue = 0, payroll = 0;
        for (ResultData.LedgerPoint lp : r.ledger) {
            if ("task_reward".equals(lp.category)) revenue += lp.amountCents;
            if ("monthly_payroll".equals(lp.category)) payroll += Math.abs(lp.amountCents);
        }
        int ok = (int) r.tasks.stream().filter(t -> t.success).count();
        int fail = (int) r.tasks.stream().filter(t -> !t.success).count();
        System.out.printf("%n=== %s ===%n", r.shortName());
        System.out.printf("  Model:    %s  |  Seed: %d%n", r.model, r.seed);
        System.out.printf("  Terminal: %s at turn %d%n", r.terminalReason, r.turnsCompleted);
        System.out.printf("  Revenue:  $%,.0f  |  Payroll: $%,.0f%n", revenue / 100.0, payroll / 100.0);
        System.out.printf("  Tasks:    %d OK, %d fail%n", ok, fail);
        System.out.printf("  Cost:     $%.2f%n", r.totalCostUsd);
    }
}
