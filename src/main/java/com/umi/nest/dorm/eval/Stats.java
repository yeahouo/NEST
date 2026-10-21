package com.umi.nest.dorm.eval;

import java.util.Arrays;
import java.util.Random;

/**
 * 实验统计工具：描述统计 + 配对显著性检验 + 自助置信区间。
 *
 * <p><b>为什么必须是"配对"检验。</b>同一组配置、同一个随机种子生成的两次分配，
 * 面对的是同一批学生、同一批房间、同一批志愿，差异只来自算法本身。
 * 把它们当独立样本做两样本 t 检验，等于把"这批学生本身就好带"的方差算进算法里，
 * 白白丢掉统计功效。配对的单位是 (配置, 种子)，{@code a[i]} 与 {@code b[i]}
 * 必须来自同一次生成——调用方保证这一点。
 *
 * <p><b>为什么 t 检验和 Wilcoxon 都报。</b>指标里有明显不对称的（阻塞对是计数、
 * 最差相容度是极值），正态假设站不住；Wilcoxon 只依赖差值的符号与秩，更稳健。
 * 两个都报，结论一致时才敢下判断；不一致就在报告里写"不一致"，不能挑好看的那个。
 *
 * <p><b>为什么还要自助区间。</b>n=10 个种子的配对 t 检验功效很低，
 * "p=0.7"只说明"这批样本没测出差异"，不说明"没有差异"。
 * 自助区间给出效应量的量级，比一个二元的显著/不显著更有信息量。
 */
public final class Stats {

    private Stats() {
    }

    // ------------------------------------------------------------------ 描述统计

    /** 一组样本的描述统计。{@code n} 是有效样本数（NaN 已被剔除）。 */
    public record Summary(int n, double mean, double std, double min, double max, double median) {

        public boolean empty() {
            return n == 0;
        }

        /** "均值±标准差"的紧凑写法，结题报告表格直接用。 */
        public String pm() {
            return n == 0 ? "—" : String.format("%.4f±%.4f", mean, std);
        }
    }

    public static Summary summarize(double[] xs) {
        if (xs == null) {
            return new Summary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        double[] v = finite(xs);
        if (v.length == 0) {
            return new Summary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        return new Summary(v.length, mean(v), std(v), v[0], v[v.length - 1], medianOfSorted(v));
    }

    /** 算术平均；空数组返回 NaN。 */
    public static double mean(double[] xs) {
        double[] v = finite(xs);
        if (v.length == 0) {
            return Double.NaN;
        }
        double s = 0;
        for (double x : v) {
            s += x;
        }
        return s / v.length;
    }

    /**
     * <b>样本</b>标准差（除以 n−1）。
     * 用总体标准差（除以 n）会系统性低估，n 越小越严重——实验里 n 常常只有 10。
     */
    public static double std(double[] xs) {
        double[] v = finite(xs);
        if (v.length < 2) {
            return 0.0;
        }
        double m = mean(v);
        double s = 0;
        for (double x : v) {
            s += (x - m) * (x - m);
        }
        return Math.sqrt(s / (v.length - 1));
    }

    public static double median(double[] xs) {
        double[] v = finite(xs);
        if (v.length == 0) {
            return Double.NaN;
        }
        return medianOfSorted(v);
    }

    private static double medianOfSorted(double[] sorted) {
        int n = sorted.length;
        return (n % 2 == 1) ? sorted[n / 2] : 0.5 * (sorted[n / 2 - 1] + sorted[n / 2]);
    }

    /** 剔除 NaN / Inf 并升序排序。 */
    public static double[] finite(double[] xs) {
        if (xs == null) {
            return new double[0];
        }
        double[] out = Arrays.stream(xs).filter(Double::isFinite).toArray();
        Arrays.sort(out);
        return out;
    }

    // ------------------------------------------------------------------ 配对 t 检验

    /**
     * 配对 t 检验（双侧）。
     *
     * @param a 处理组（如 NEST）
     * @param b 对照组（如 B0）
     */
    public record PairedT(int n, double meanDiff, double stdDiff, double t, double pTwoSided, double cohensD,
                          double ciLow, double ciHigh) {

        /**
         * 按 p 值给结论。
         *
         * <p><b>警告：本方法不知道指标方向</b>——它只看"处理组数值是否更大"。
         * 对"越小越好"的指标（房内方差、阻塞对数、耗时）它会给出完全相反的措辞。
         * 调用方必须结合"越大越好/越小越好"自己重新措辞，不要直接用它。
         */
        public String verdict(double alpha) {
            if (n < 2) {
                return "样本不足";
            }
            if (!Double.isFinite(pTwoSided)) {
                return "不可判定";
            }
            if (pTwoSided < alpha) {
                return meanDiff > 0 ? "显著更优" : "显著更差";
            }
            return "无显著差异";
        }
    }

    /** 配对 t 检验；自动剔除任一侧非有限的配对。{@code alpha} 用 0.05。 */
    public static PairedT pairedT(double[] a, double[] b) {
        double[] d = pairedDiff(a, b);
        int n = d.length;
        if (n < 2) {
            return new PairedT(n, n == 1 ? d[0] : Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN);
        }
        double m = mean(d);
        double sd = std(d);
        if (sd < 1e-12) {
            // 所有差值完全相同：标准差为 0，t 无穷大。p 值取 0（若均值为 0 则无差异）。
            double p = Math.abs(m) < 1e-12 ? 1.0 : 0.0;
            double t = Math.abs(m) < 1e-12 ? 0.0 : (m > 0 ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY);
            return new PairedT(n, m, 0.0, t, p, Double.NaN, m, m);
        }
        double se = sd / Math.sqrt(n);
        double t = m / se;
        double p = studentTTwoSided(t, n - 1);
        double d2 = m / sd;
        // 均值的 95% 置信区间：t_{0.975, n-1} * SE
        double tc = tQuantile975(n - 1);
        return new PairedT(n, m, sd, t, p, d2, m - tc * se, m + tc * se);
    }

    /**
     * 双侧 Student-t 的 p 值。
     * <p>{@code P(|T| > |t|) = I_{df/(df+t²)}(df/2, 1/2)}，I 是正则化不完全 Beta 函数。
     */
    public static double studentTTwoSided(double t, int df) {
        if (df <= 0 || !Double.isFinite(t)) {
            return Double.NaN;
        }
        double x = df / (df + t * t);
        return regularizedIncompleteBeta(df / 2.0, 0.5, x);
    }

    /** t 分布 97.5% 分位数（用于 95% 置信区间），必要时用 Newton 迭代反解。 */
    public static double tQuantile975(int df) {
        if (df <= 0) {
            return Double.NaN;
        }
        if (df > 2000) {
            return 1.959964; // 已与正态不可区分
        }
        double lo = 0.0;
        double hi = 100.0;
        for (int i = 0; i < 200; i++) {
            double mid = 0.5 * (lo + hi);
            // 双侧 p = 0.05 ⇔ CDF(mid) = 0.975
            double p2 = studentTTwoSided(mid, df);
            if (p2 > 0.05) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return 0.5 * (lo + hi);
    }

    // ------------------------------------------------------------------ Wilcoxon 符号秩

    /**
     * Wilcoxon 符号秩检验（双侧，正态近似）。
     *
     * <p>零差值按惯例全部丢弃（而不是当作 0 秩），这对"很多次种子下差异恰好为 0"
     * 的计数型指标（阻塞对）是必须的：把零当最小秩会把 p 值算得偏小。
     * 并列用平均秩并施加并列校正，连续性校正 ±0.5。
     */
    public record Wilcoxon(int n, double wPlus, double wMinus, double z, double pTwoSided) {

        /** p < 0.05 即认为差异显著。 */
        public boolean significant() {
            return Double.isFinite(pTwoSided) && pTwoSided < 0.05;
        }

        /**
         * 该样本量下 Wilcoxon 双侧检验<b>物理上能达到的最小 p</b>（无并列时）。
         *
         * <p>符号秩检验把 2ⁿ 种符号组合视为等概率，最小双侧 p = 2/2ⁿ = 2^(1−n)。
         * 于是 n=5 时最小可能 p = 0.0625 —— <b>无论数据多极端都不可能显著</b>；
         * n=6 时才降到 0.03125。并列只会让 p 更大，所以这是真正的下界。
         *
         * <p>不知道这条，就会在 n=5 的报告里写满"无显著差异"，
         * 而同一行的配对 t 检验写着 p&lt;0.001、Cohen's d=21 —— 把"检验没有功效"
         * 误报成"没有差异"，是实验报告里最严重的一类错误。
         */
        public static double minAttainableP(int n) {
            return n <= 0 ? 1.0 : Math.pow(2.0, 1.0 - n);
        }

        /**
         * 按 p 值给结论。
         *
         * <p><b>警告：本方法不知道指标方向</b>——它只看"处理组数值是否更大"。
         * 对"越小越好"的指标（房内方差、阻塞对数、耗时）它会给出完全相反的措辞。
         * 这正是本项目踩过的坑：报告里"房内方差下降 36.8%"被配上了"显著更差"，
         * "耗时涨了 65 倍"被配上了"显著更优"。调用方必须结合"越大越好/越小越好"重新措辞。
         */
        public String verdict(double alpha) {
            if (n < 1) {
                return "样本不足";
            }
            if (!Double.isFinite(pTwoSided)) {
                return "不可判定";
            }
            if (pTwoSided < alpha) {
                return wPlus > wMinus ? "显著更优" : "显著更差";
            }
            return "无显著差异";
        }
    }

    public static Wilcoxon wilcoxon(double[] a, double[] b) {
        double[] d = pairedDiff(a, b);
        // 丢弃零差值，但先统计有多少个零（报告里需要）
        double[] nz = Arrays.stream(d).filter(x -> Math.abs(x) > 1e-12).toArray();
        int n = nz.length;
        if (n == 0) {
            return new Wilcoxon(0, 0, 0, Double.NaN, Double.NaN);
        }
        double[] abs = new double[n];
        for (int i = 0; i < n; i++) {
            abs[i] = Math.abs(nz[i]);
        }
        double[] ranks = rank(abs);
        double wPlus = 0;
        double wMinus = 0;
        for (int i = 0; i < n; i++) {
            if (nz[i] > 0) {
                wPlus += ranks[i];
            } else {
                wMinus += ranks[i];
            }
        }
        double meanW = n * (n + 1) / 4.0;
        double tieSum = tieCorrection(abs);
        double varW = n * (n + 1) * (2 * n + 1) / 24.0 - tieSum;
        if (varW <= 1e-12) {
            return new Wilcoxon(n, wPlus, wMinus, Double.NaN, Double.NaN);
        }
        double z = (wPlus - meanW - Math.signum(wPlus - meanW) * 0.5) / Math.sqrt(varW);
        double p = 2 * (1 - normalCdf(Math.abs(z)));
        return new Wilcoxon(n, wPlus, wMinus, z, Math.min(1.0, p));
    }

    /** 平均秩（并列取平均），与 ExtractorEvaluator 里的口径一致。 */
    public static double[] rank(double[] xs) {
        int n = xs.length;
        Integer[] idx = new Integer[n];
        for (int i = 0; i < n; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (p, q) -> Double.compare(xs[p], xs[q]));
        double[] r = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && Math.abs(xs[idx[j + 1]] - xs[idx[i]]) < 1e-12) {
                j++;
            }
            double avg = (i + j) / 2.0 + 1;
            for (int k = i; k <= j; k++) {
                r[idx[k]] = avg;
            }
            i = j + 1;
        }
        return r;
    }

    /** Wilcoxon 并列校正项 Σ(t³−t)/48。 */
    private static double tieCorrection(double[] xs) {
        double[] sorted = xs.clone();
        Arrays.sort(sorted);
        double sum = 0;
        int i = 0;
        while (i < sorted.length) {
            int j = i;
            while (j + 1 < sorted.length && Math.abs(sorted[j + 1] - sorted[i]) < 1e-12) {
                j++;
            }
            long t = j - i + 1L;
            if (t > 1) {
                sum += t * t * t - t;
            }
            i = j + 1;
        }
        return sum / 48.0;
    }

    /** 标准正态 CDF，误差 < 7.5e-8（Abramowitz & Stegun 26.2.17）。 */
    public static double normalCdf(double z) {
        if (z < 0) {
            return 1 - normalCdf(-z);
        }
        double t = 1.0 / (1.0 + 0.2316419 * z);
        double poly = t * (0.319381530 + t * (-0.356563782 + t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))));
        double phi = Math.exp(-0.5 * z * z) / Math.sqrt(2 * Math.PI);
        return 1 - phi * poly;
    }

    // ------------------------------------------------------------------ 自助置信区间

    /**
     * 配对差值均值的百分位自助置信区间。
     *
     * <p>种子固定，所以同一份数据每次跑出同样的区间——实验必须可复现，
     * 一个每次都不一样的置信区间没法写进结题报告。
     */
    public static double[] bootstrapCI(double[] diff, double alpha, long seed, int iters) {
        double[] d = finite(diff);
        if (d.length < 2) {
            return new double[]{Double.NaN, Double.NaN};
        }
        Random rnd = new Random(seed);
        double[] means = new double[iters];
        for (int i = 0; i < iters; i++) {
            double s = 0;
            for (int j = 0; j < d.length; j++) {
                s += d[rnd.nextInt(d.length)];
            }
            means[i] = s / d.length;
        }
        Arrays.sort(means);
        int lo = (int) Math.floor(alpha / 2 * iters);
        int hi = (int) Math.ceil((1 - alpha / 2) * iters) - 1;
        return new double[]{means[Math.max(0, Math.min(iters - 1, lo))], means[Math.max(0, Math.min(iters - 1, hi))]};
    }

    // ------------------------------------------------------------------ 内部数学

    private static double[] pairedDiff(double[] a, double[] b) {
        if (a == null || b == null) {
            return new double[0];
        }
        int n = Math.min(a.length, b.length);
        double[] d = new double[n];
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (Double.isFinite(a[i]) && Double.isFinite(b[i])) {
                d[k++] = a[i] - b[i];
            }
        }
        return Arrays.copyOf(d, k);
    }

    /** 正则化不完全 Beta 函数 I_x(a,b)。 */
    public static double regularizedIncompleteBeta(double a, double b, double x) {
        if (x <= 0) {
            return 0.0;
        }
        if (x >= 1) {
            return 1.0;
        }
        double bt = Math.exp(logGamma(a + b) - logGamma(a) - logGamma(b) + a * Math.log(x) + b * Math.log(1 - x));
        if (x < (a + 1) / (a + b + 2)) {
            return bt * betaContinuedFraction(a, b, x) / a;
        }
        return 1 - bt * betaContinuedFraction(b, a, 1 - x) / b;
    }

    /** 连分式展开（Lentz 算法），Numerical Recipes betacf 的等价实现。 */
    private static double betaContinuedFraction(double a, double b, double x) {
        final double eps = 3e-12;
        final double fpmin = 1e-30;
        double qab = a + b;
        double qap = a + 1;
        double qam = a - 1;
        double c = 1.0;
        double d = 1.0 - qab * x / qap;
        if (Math.abs(d) < fpmin) {
            d = fpmin;
        }
        d = 1.0 / d;
        double h = d;
        for (int m = 1; m <= 300; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < fpmin) {
                d = fpmin;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < fpmin) {
                c = fpmin;
            }
            d = 1.0 / d;
            h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + aa * d;
            if (Math.abs(d) < fpmin) {
                d = fpmin;
            }
            c = 1.0 + aa / c;
            if (Math.abs(c) < fpmin) {
                c = fpmin;
            }
            d = 1.0 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1.0) < eps) {
                break;
            }
        }
        return h;
    }

    /** Lanczos 近似的 ln Γ(x)，误差 ~1e-10。 */
    public static double logGamma(double x) {
        double[] cof = {
                76.18009172947146, -86.50532032941677, 24.01409824083091,
                -1.231739572450155, 0.1208650973866179e-2, -0.5395239384953e-5
        };
        double y = x;
        double tmp = x + 5.5;
        tmp -= (x + 0.5) * Math.log(tmp);
        double ser = 1.000000000190015;
        for (int j = 0; j < 6; j++) {
            ser += cof[j] / ++y;
        }
        return -tmp + Math.log(2.5066282746310005 * ser / x);
    }

    /** 数值格式化：NaN 一律显示为 "—"，避免报告里出现 "NaN"。 */
    public static String fmt(double v, int digits) {
        return Double.isFinite(v) ? String.format("%." + digits + "f", v) : "—";
    }

    /** p 值格式化：小于 0.001 时不用一堆 0 占位。 */
    public static String fmtP(double p) {
        if (!Double.isFinite(p)) {
            return "—";
        }
        if (p < 0.001) {
            return "<0.001";
        }
        return String.format("%.4f", p);
    }
}
