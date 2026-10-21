import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.LinearExpr;
import com.google.ortools.sat.LinearExprBuilder;

/**
 * 一次性二分探针：用与 CpSatJudge.BlockPlan 完全相同的建模方式，
 * 在一个"12 人 / 3 房 / 每房 4 人"的平凡可行实例上逐个剥离部件，
 * 找出 CP-SAT 判 INFEASIBLE 的真正来源。
 *
 * 结论（已定案）：AND 线性化第三条约束的方向写反了。
 * {@code addGreaterOrEqual(a, b)} 的语义是 {@code a ≥ b}，
 * 原实现写的是 {@code addGreaterOrEqual(x_i+x_j−1, z)} 即 {@code z ≤ x_i+x_j−1}，
 * 配合 {@code z ≥ 0} 变成"每一对都必须同房"，12 人每房 4 人自然无解。
 * 正确写法是 {@code addGreaterOrEqual(z, x_i+x_j−1)} 即 {@code z ≥ x_i+x_j−1}。
 *
 * 运行（必须 JDK 25，JDK 17/23 会让 OR-Tools 原生库崩溃）：
 *   javac -encoding UTF-8 -cp "target\classes;$cp" -d target\tools tools\CpSatBisect.java
 *   java -cp "target\tools;$cp" CpSatBisect
 */
public class CpSatBisect {

    public static void main(String[] args) {
        Loader.loadNativeLibraries();
        int students = 12;
        int rooms = 3;
        int cap = 4;

        build("A 只建 x + Σ=1 + 容量", students, rooms, cap, false, false, false);
        build("B 加 z 三元组（旧的方向），无目标", students, rooms, cap, true, false, true);
        build("C 加 z（旧方向）与目标 = 原实现", students, rooms, cap, true, true, true);
        build("D 目标用空表达式", students, rooms, cap, false, true, false);
        build("E 加 z 三元组（修正方向），无目标", students, rooms, cap, true, false, false);
        build("F 加 z（修正方向）与目标 = 现实现", students, rooms, cap, true, true, false);
    }

    private static void build(String label, int S, int R, int CAP,
                             boolean withZ, boolean withObj, boolean wrongDirection) {
        CpModel model = new CpModel();

        BoolVar[][] x = new BoolVar[S][R];
        for (int i = 0; i < S; i++) {
            LinearExprBuilder b = LinearExpr.newBuilder();
            for (int r = 0; r < R; r++) {
                x[i][r] = model.newBoolVar("x_" + i + "_" + r);
                b.add(x[i][r]);
            }
            model.addEquality(b.build(), 1);
        }
        for (int r = 0; r < R; r++) {
            LinearExprBuilder b = LinearExpr.newBuilder();
            for (int i = 0; i < S; i++) {
                b.add(x[i][r]);
            }
            model.addLessOrEqual(b.build(), CAP);
        }

        LinearExprBuilder obj = LinearExpr.newBuilder();
        if (withZ) {
            for (int i = 0; i < S; i++) {
                for (int j = i + 1; j < S; j++) {
                    for (int r = 0; r < R; r++) {
                        BoolVar z = model.newBoolVar("z_" + i + "_" + j + "_" + r);
                        model.addLessOrEqual(z, x[i][r]);
                        model.addLessOrEqual(z, x[j][r]);
                        LinearExpr adj = LinearExpr.newBuilder().add(x[i][r]).add(x[j][r]).add(-1).build();
                        if (wrongDirection) {
                            // z ≤ x_i + x_j − 1   ← 就是这里写反了
                            model.addGreaterOrEqual(adj, z);
                        } else {
                            // z ≥ x_i + x_j − 1   ← 正确
                            model.addGreaterOrEqual(z, adj);
                        }
                        if (withObj) {
                            obj.addTerm(z, -526282L);
                        }
                    }
                }
            }
        }
        if (withObj) {
            model.maximize(obj.build());
        }

        CpSolver solver = new CpSolver();
        solver.getParameters().setNumSearchWorkers(8);
        solver.getParameters().setMaxTimeInSeconds(10);
        CpSolverStatus status = solver.solve(model);
        System.out.printf("%-38s -> %-12s vars=%d cons=%d validate=[%s]%n",
                label, status, model.model().getVariablesCount(),
                model.model().getConstraintsCount(), model.validate());
    }
}
