package com.umi.nest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Controller;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import java.beans.Introspector;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 常设闸门：Spring 组件扫描期不允许「不同类、同一 bean 名」。
 *
 * <p>真实事故（2026-10-16，P14 心理健康后端落地后第一次重启）：新写的
 * {@code com.umi.nest.controller.PsycheController} 与既有的
 * {@code com.umi.nest.dorm.web.PsycheController} 默认 bean 名同为 {@code psycheController}，
 * 启动直接
 * {@code ConflictingBeanDefinitionException: Annotation-specified bean name 'psycheController' for bean
 * class [com.umi.nest.dorm.web.PsycheController] conflicts with existing, non-compatible bean definition of
 * same name and class [com.umi.nest.controller.PsycheController]}
 * —— 应用整个起不来，而 {@code mvn test} 全绿（单测只 new 目标类、不跑组件扫描），
 * {@code check-mapper-columns.mjs} 也只看列名，静态门禁全漏。
 *
 * <p>判据：扫 {@code target/classes} 下所有 {@code .class}，用
 * {@code Class.forName(name, false, loader)}（不初始化，避免副作用）读注解，算出 Spring 的默认 bean 名
 * （注解显式 value 优先，否则 {@code Introspector.decapitalize(简单类名)}），断言不存在重复；
 * 并额外要求「同名类必须各自显式命名 bean」，防止有人把显式名删掉后事故复活。</p>
 */
@DisplayName("常设闸门：Spring bean 名不允许冲突（同名类必须显式命名）")
class SpringBeanNameCollisionTest {

    private static final class Bean {
        final String beanName;
        final String className;
        final boolean explicit;

        Bean(String beanName, String className, boolean explicit) {
            this.beanName = beanName;
            this.className = className;
            this.explicit = explicit;
        }
    }

    /**
     * bean 名的候选注解，**顺序＝优先级**（`@RestController` 元注解里含 `@Controller`、
     * `@Controller/@Service/@Repository` 元注解里含 `@Component`，JDK 的 {@code isAnnotationPresent}
     * 不做元注解查找，必须用 {@link AnnotationUtils#findAnnotation}）。
     */
    private static final List<Class<? extends Annotation>> STEREOTYPES = List.of(
            RestController.class, Controller.class, Service.class, Repository.class,
            Component.class, Configuration.class);

    @Test
    @DisplayName("扫描 target/classes：无重复 bean 名，且同名类都显式命名")
    void noConflictingBeanNames() throws IOException, URISyntaxException {
        Path root = Path.of(NestApplication.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(root), "取不到 target/classes 目录：" + root);

        Map<String, List<String>> byBeanName = new LinkedHashMap<>();
        Map<String, List<Bean>> bySimpleName = new LinkedHashMap<>();
        int scanned = 0;
        int skipped = 0;

        List<Path> classFiles = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                    .filter(p -> !p.getFileName().toString().contains("$"))
                    .filter(p -> !p.getFileName().toString().startsWith("module-info"))
                    .filter(p -> !p.getFileName().toString().startsWith("package-info"))
                    .forEach(classFiles::add);
        }

        ClassLoader loader = getClass().getClassLoader();
        for (Path p : classFiles) {
            String relative = root.relativize(p).toString().replace('\\', '/');
            String fqcn = relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
            Class<?> c;
            try {
                c = Class.forName(fqcn, false, loader);
            } catch (Throwable t) {
                skipped++;
                continue;
            }
            if (c.isInterface() || c.isEnum() || c.isAnnotation()) {
                continue;
            }
            if (AnnotationUtils.findAnnotation(c, Component.class) == null) {
                continue;
            }
            scanned++;
            String explicitName = null;
            for (Class<? extends Annotation> stereotype : STEREOTYPES) {
                Annotation a = AnnotationUtils.findAnnotation(c, stereotype);
                if (a == null) {
                    continue;
                }
                String v = explicitValue(a);
                if (v != null && !v.isEmpty()) {
                    explicitName = v;
                    break;
                }
            }
            String beanName = explicitName != null ? explicitName : Introspector.decapitalize(c.getSimpleName());
            byBeanName.computeIfAbsent(beanName, k -> new ArrayList<>()).add(fqcn);
            bySimpleName.computeIfAbsent(c.getSimpleName(), k -> new ArrayList<>())
                    .add(new Bean(beanName, fqcn, explicitName != null));
        }

        assertTrue(scanned >= 20, "只扫到 " + scanned + " 个 @Component 类（跳过 " + skipped
                + "，class 文件 " + classFiles.size() + " 个，root=" + root
                + "），classpath 可能没找对 —— 这条闸门不能空绿");

        List<String> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byBeanName.entrySet()) {
            if (e.getValue().size() > 1) {
                conflicts.add("bean 名 " + e.getKey() + " ← " + String.join(", ", e.getValue()));
            }
        }
        assertTrue(conflicts.isEmpty(),
                "Spring bean 名冲突（应用启动会 ConflictingBeanDefinitionException）：\n  " + String.join("\n  ", conflicts));

        List<String> unlabeled = new ArrayList<>();
        for (Map.Entry<String, List<Bean>> e : bySimpleName.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            for (Bean b : e.getValue()) {
                if (!b.explicit) {
                    unlabeled.add(b.className + "（同名类，但 bean 名是默认值 " + b.beanName + "）");
                }
            }
        }
        assertTrue(unlabeled.isEmpty(),
                "同名类必须显式命名 bean（否则删掉显式名就复活冲突）：\n  " + String.join("\n  ", unlabeled));

        System.out.println("Spring bean 名扫描：@Component 类 " + scanned + " 个（跳过 " + skipped
                + "），bean 名 " + byBeanName.size() + " 个，冲突 0");
    }

    /** 取注解的 value()（String 或 String[]），非 bean 名注解返回 null。 */
    private static String explicitValue(Annotation a) {
        try {
            Object v = a.annotationType().getMethod("value").invoke(a);
            if (v instanceof String s) {
                return s;
            }
            if (v instanceof String[] arr && arr.length > 0) {
                return arr[0];
            }
        } catch (ReflectiveOperationException ignored) {
            // 没有 value() 的注解（例如 @RequestBody 之类）—— 与 bean 名无关
        }
        return null;
    }
}
