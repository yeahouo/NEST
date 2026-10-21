import com.google.ortools.Loader;
import com.google.ortools.sat.BoolVar;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.LinearExpr;

/**
 * 最小探针：验证 OR-Tools 原生库在本机能否真正求解（不只是加载）。
 * 现在已知的故障是 abseil_dll.dll 调用 msvcp140.dll 时崩溃，
 * 而崩的是 JDK 自带的旧版 msvcp140（exe 所在目录优先于 System32）。
 * 用法：java -cp ... DllProbe [preload]
 */
public class DllProbe {
    public static void main(String[] args) {
        boolean preload = args.length > 0 && "preload".equals(args[0]);
        if (preload) {
            for (String dll : new String[]{"msvcp140.dll", "msvcp140_1.dll", "msvcp140_2.dll"}) {
                try {
                    System.load("C:/Windows/System32/" + dll);
                    System.out.println("preloaded " + dll);
                } catch (Throwable t) {
                    System.out.println("preload " + dll + " failed: " + t.getClass().getSimpleName());
                }
            }
        }
        Loader.loadNativeLibraries();
        System.out.println("natives loaded OK");
        CpModel m = new CpModel();
        BoolVar x = m.newBoolVar("x");
        BoolVar y = m.newBoolVar("y");
        m.addEquality(LinearExpr.newBuilder().add(x).add(y).build(), 1);
        m.maximize(LinearExpr.term(x, 3));
        CpSolver s = new CpSolver();
        System.out.println("status=" + s.solve(m) + " objective=" + s.objectiveValue()
                + " x=" + s.booleanValue(x) + " y=" + s.booleanValue(y));
    }
}
