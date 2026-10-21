# 把 course-detail.html（学生端课程页，2206 行）改造成 nx-* 设计层。
#
# 为什么用脚本而不是逐条 edit：
#   ① 同一段 class 串在文件里出现多次，逐条 edit 容易漏；
#   ② 每处替换都带「必须恰好命中 N 次」的断言，命中数不对就整份不写，不会静默改坏；
#   ③ 2206 行的文件手工改完无法复现，脚本可以重跑、可以 code review。
#
# 顺带修掉的真实缺陷：
#   - 课程头写死 "Advanced React Patterns" / "Dr. Sarah Jenkins" / "6 Credit Hours" / "Fall 2024"；
#   - loadCourseInfo 用 document.querySelector('h1') 与 .flex.flex-wrap.items-center.gap-6 这种
#     脆弱选择器定位，且失败只 console.error（页头永远是假数据）；
#   - 五个面板的 catch 只 console.error，加载失败时页面留一片空白/骨架，看起来像「没有数据」。
$ErrorActionPreference = 'Stop'
$file = 'D:\project\NEST\src\main\resources\static\course-detail.html'
$script:log = @()
$script:fail = 0

function Edit-File {
    param(
        [string]$Pattern,
        [string]$Replacement,
        [string]$Label,
        [int]$Expect = 1,
        [switch]$Literal
    )
    $orig = [System.IO.File]::ReadAllText($file)
    $pat = if ($Literal) { [System.Text.RegularExpressions.Regex]::Escape($Pattern) } else { $Pattern }
    $hits = ([System.Text.RegularExpressions.Regex]::Matches($orig, $pat)).Count
    if ($hits -ne $Expect) {
        $script:fail++
        $script:log += "FAIL [$Label] 命中 $hits 次，期望 $Expect 次 —— 未写入"
        return
    }
    $new = if ($Literal) {
        $orig.Replace($Pattern, $Replacement)
    } else {
        # 替换串里有 ${...}（JS 模板字面量），必须先中和 .NET 的 $ 替换语义
        [System.Text.RegularExpressions.Regex]::Replace($orig, $pat, $Replacement.Replace('$', '$$'))
    }
    if ($new -eq $orig) {
        $script:fail++
        $script:log += "FAIL [$Label] 命中 $hits 次但没有产生变化"
        return
    }
    [System.IO.File]::WriteAllText($file, $new, [System.Text.UTF8Encoding]::new($false))
    $script:log += "ok   [$Label] 替换 $hits 处"
}

# ================================================================ 页面外壳
$p = @'
<body class="font-display bg-background-light dark:bg-background-dark text-slate-900 dark:text-slate-100 selection:bg-primary/30 overflow-x-hidden">
'@
$r = @'
<body class="nx-page font-display text-slate-900 dark:text-slate-100 selection:bg-primary/30 overflow-x-hidden">
<div class="nx-aurora nx-grid fixed inset-0 -z-10"></div>
'@
Edit-File -Pattern $p -Replacement $r -Label 'L01 body -> nx-page + aurora' -Literal -Expect 1

# ================================================================ 课程头（整块换掉：写死的课程名/教师/学分/学期）
$p = '(?s)<div class="flex flex-col lg:flex-row lg:items-center justify-between gap-8 card-animate">.*?<!-- 学生签到弹窗 -->'
$r = @'
<div class="card-animate">
                    <div class="flex items-center gap-2 text-primary font-bold text-sm mb-3 uppercase tracking-widest">
                        <span class="material-symbols-outlined text-lg">arrow_back</span>
                        <a class="nx-link" href="dashboard.html">Back to Dashboard</a>
                    </div>
                    <div class="nx-card nx-accent-top p-7 flex flex-col lg:flex-row lg:items-center justify-between gap-8">
                        <div class="flex-1 min-w-0">
                            <h1 id="course-title" class="text-3xl md:text-4xl font-black tracking-tight">Loading course&hellip;</h1>
                            <div id="course-meta" class="flex flex-wrap items-center gap-x-6 gap-y-2 mt-4 text-sm font-medium nx-muted">
                                <span class="flex items-center gap-1.5"><span class="material-symbols-outlined text-lg animate-pulse">sync</span>Loading&hellip;</span>
                            </div>
                        </div>
                        <div class="flex flex-col items-start lg:items-end gap-3 shrink-0 hidden" id="attendance-section">
                            <div class="flex items-center gap-2 px-4 py-2 rounded-full bg-slate-100 dark:bg-slate-800/50 text-slate-500 dark:text-slate-400 text-sm font-semibold">
                                <span class="relative flex h-2 w-2">
                                    <span class="animate-ping absolute inline-flex h-full w-full rounded-full bg-green-400 opacity-75"></span>
                                    <span class="relative inline-flex rounded-full h-2 w-2 bg-green-500"></span>
                                </span>
                                Session Live
                            </div>
                            <button onclick="openCheckinModal()" class="pulse-primary flex items-center gap-3 h-14 px-8 bg-primary text-white font-black hover:brightness-110 transition-all shadow-xl shadow-primary/30 text-lg uppercase tracking-tight rounded-full">
                                <span class="material-symbols-outlined text-2xl">qr_code_scanner</span>
                                Confirm Attendance
                            </button>
                        </div>
                    </div>
                </div>

                <!-- 学生签到弹窗 -->
'@
Edit-File -Pattern $p -Replacement $r -Label 'L02 hero (drop hardcoded name/teacher/credits/semester)' -Expect 1

# ================================================================ tab 栏
$p = '<div class="flex flex-wrap gap-2 border-b border-primary/10 pb-1 card-animate" id="tab-navigation" style="animation-delay: 0.1s;">'
$r = '<div class="flex flex-wrap gap-1.5 p-1.5 nx-glass rounded-2xl border border-white/50 dark:border-white/5 card-animate" id="tab-navigation" style="animation-delay: 0.1s;">'
Edit-File -Pattern $p -Replacement $r -Label 'L03 tab bar shell' -Literal -Expect 1

$activeOld = '<button class="tab-btn px-6 py-3 bg-primary text-white font-bold text-sm transition-all rounded-xl" data-tab="learning-materials"'
Edit-File -Pattern $activeOld -Replacement '<button class="tab-btn nx-tab is-active" data-tab="learning-materials"' -Label 'L04 tab learning-materials' -Literal -Expect 1

$inactiveOld = '<button class="tab-btn px-6 py-3 text-slate-500 dark:text-slate-400 hover:text-primary font-bold text-sm transition-all hover:bg-primary/5 rounded-xl" data-tab="'
foreach ($tab in @('assignments', 'quizzes', 'announcements', 'attendance-record')) {
    Edit-File -Pattern ($inactiveOld + $tab + '"') -Replacement ('<button class="tab-btn nx-tab" data-tab="' + $tab + '"') -Label ("L04 tab " + $tab) -Literal -Expect 1
}

# ================================================================ switchTab 只用 is-active
$p = @'
buttons.forEach(btn => {
                btn.classList.remove('bg-primary', 'text-white');
                btn.classList.add('text-slate-500', 'dark:text-slate-400', 'hover:text-primary', 'hover:bg-primary/5');
            });
'@
Edit-File -Pattern $p -Replacement '            buttons.forEach(btn => btn.classList.remove(''is-active''));' -Label 'L05 switchTab reset' -Literal -Expect 1

$p = @'
targetBtn.classList.add('bg-primary', 'text-white');
                targetBtn.classList.remove('text-slate-500', 'dark:text-slate-400', 'hover:text-primary', 'hover:bg-primary/5');
'@
Edit-File -Pattern $p -Replacement '                targetBtn.classList.add(''is-active'');' -Label 'L06 switchTab activate' -Literal -Expect 1

# ================================================================ 各类卡片 -> nx-card
$p = @'
<button class="item-card group p-5 border border-primary/10 bg-white/50 dark:bg-slate-800/30 backdrop-blur-md hover:border-primary/40 hover:bg-white/80 dark:hover:bg-slate-800/50 transition-all text-left ghost-layer rounded-3xl" style="${delay}" onclick="navigateToFolder(${item.id})">
'@
Edit-File -Pattern $p -Replacement '<button class="item-card nx-card nx-card-i p-5 text-left" style="${delay}" onclick="navigateToFolder(${item.id})">' -Label 'L07 material folder card' -Literal -Expect 1

$p = @'
<button class="item-card group p-5 border border-primary/10 bg-white/50 dark:bg-slate-800/30 backdrop-blur-md hover:border-primary/40 hover:bg-white/80 dark:hover:bg-slate-800/50 transition-all text-left ghost-layer rounded-3xl" style="${delay}" onclick="downloadFile(${item.id})">
'@
Edit-File -Pattern $p -Replacement '<button class="item-card nx-card nx-card-i p-5 text-left" style="${delay}" onclick="downloadFile(${item.id})">' -Label 'L08 material file card' -Literal -Expect 1

$p = @'
<div class="p-6 border border-primary/10 bg-white/50 dark:bg-slate-800/30 backdrop-blur-md rounded-3xl flex items-center justify-between group hover:bg-white/80 dark:hover:bg-slate-800/50 transition-all cursor-pointer" onclick="openAssignmentModal(${a.id})">
'@
Edit-File -Pattern $p -Replacement '<div class="nx-card nx-card-i p-6 flex items-center justify-between gap-4 cursor-pointer" onclick="openAssignmentModal(${a.id})">' -Label 'L09 assignment card' -Literal -Expect 1

$p = @'
<div class="p-6 border border-primary/10 bg-white/50 dark:bg-slate-800/30 backdrop-blur-md rounded-3xl flex items-center justify-between gap-4 group hover:bg-white/80 dark:hover:bg-slate-800/50 transition-all">
'@
Edit-File -Pattern $p -Replacement '<div class="nx-card nx-card-i p-6 flex items-center justify-between gap-4">' -Label 'L10 quiz card' -Literal -Expect 1

$p = @'
<div class="p-6 border border-primary/10 bg-white/40 dark:bg-slate-800/20 backdrop-blur-xl rounded-3xl relative overflow-hidden group hover:border-primary/30 transition-all announcement-card">
'@
Edit-File -Pattern $p -Replacement '<div class="p-6 nx-card nx-card-i relative overflow-hidden announcement-card">' -Label 'L11 announcement card (long)' -Literal -Expect 1

$p = @'
<div class="p-6 border border-primary/10 bg-white/40 dark:bg-slate-800/20 backdrop-blur-xl rounded-3xl relative overflow-hidden group hover:border-primary/30 transition-all">
'@
Edit-File -Pattern $p -Replacement '<div class="p-6 nx-card nx-card-i relative overflow-hidden">' -Label 'L12 announcement card (short)' -Literal -Expect 1

# ================================================================ 载入态 -> 骨架屏（3 处 HTML + 2 处 JS）
$p = '(?s)<div class="text-center py-12 text-slate-400">\s*<span class="material-symbols-outlined text-4xl animate-pulse">sync</span>\s*<p class="mt-2">Loading [a-z]+\.\.\.</p>\s*</div>'
$r = @'
<div class="nx-skel h-20 rounded-2xl"></div>
                        <div class="nx-skel h-20 rounded-2xl"></div>
                        <div class="nx-skel h-20 rounded-2xl"></div>
'@
Edit-File -Pattern $p -Replacement $r -Label 'L13 loading placeholders -> skeleton' -Expect 5

# ================================================================ 空状态 -> nx-empty
$p = '(?s)<div class="col-span-2 text-center py-10 text-slate-500">\s*<span class="material-symbols-outlined text-4xl mb-2">folder_open</span>\s*<p>No materials yet</p>\s*</div>'
$r = @'
<div class="nx-empty col-span-2">
                        <span class="material-symbols-outlined text-3xl nx-muted">folder_open</span>
                        <p class="font-bold text-slate-900 dark:text-slate-100 mt-2">No materials yet</p>
                        <p class="text-sm nx-muted mt-1">Your teacher has not uploaded anything to this folder.</p>
                    </div>
'@
Edit-File -Pattern $p -Replacement $r -Label 'L14 empty materials' -Expect 1

$p = '(?s)<div class="text-center py-12 text-slate-400">\s*<span class="material-symbols-outlined text-4xl mb-2">assignment</span>\s*<p>No assignments available</p>\s*</div>'
$r = @'
<div class="nx-empty">
                        <span class="material-symbols-outlined text-3xl nx-muted">assignment</span>
                        <p class="font-bold text-slate-900 dark:text-slate-100 mt-2">No assignments yet</p>
                        <p class="text-sm nx-muted mt-1">There is nothing to hand in for this course right now.</p>
                    </div>
'@
Edit-File -Pattern $p -Replacement $r -Label 'L15 empty assignments' -Expect 1

$p = '(?s)<div class="text-center py-12 text-slate-400">\s*<span class="material-symbols-outlined text-4xl mb-2">quiz</span>\s*<p>No quizzes yet</p>\s*</div>'
$r = @'
<div class="nx-empty">
                        <span class="material-symbols-outlined text-3xl nx-muted">quiz</span>
                        <p class="font-bold text-slate-900 dark:text-slate-100 mt-2">No quizzes yet</p>
                        <p class="text-sm nx-muted mt-1">Your teacher has not published a quiz for this course.</p>
                    </div>
'@
Edit-File -Pattern $p -Replacement $r -Label 'L16 empty quizzes' -Expect 1

$p = '(?s)<div class="text-center py-12 text-slate-400">\s*<span class="material-symbols-outlined text-4xl mb-2">campaign</span>\s*<p>No announcements yet</p>\s*</div>'
$r = @'
<div class="nx-empty">
                        <span class="material-symbols-outlined text-3xl nx-muted">campaign</span>
                        <p class="font-bold text-slate-900 dark:text-slate-100 mt-2">No announcements yet</p>
                        <p class="text-sm nx-muted mt-1">Nothing has been posted to this course.</p>
                    </div>
'@
Edit-File -Pattern $p -Replacement $r -Label 'L17 empty announcements' -Expect 2

$p = '(?s)<tr>\s*<td colspan="3" class="px-6 py-12 text-center text-slate-400">\s*<span class="material-symbols-outlined text-4xl mb-2">event_busy</span>\s*<p>No attendance records yet</p>\s*</td>\s*</tr>'
$r = @'
<tr>
                        <td colspan="3" class="px-6 py-12">
                            <div class="nx-empty">
                                <span class="material-symbols-outlined text-3xl nx-muted">event_busy</span>
                                <p class="font-bold text-slate-900 dark:text-slate-100 mt-2">No attendance records yet</p>
                                <p class="text-sm nx-muted mt-1">Attendance will appear here after the first session is held.</p>
                            </div>
                        </td>
                    </tr>
'@
Edit-File -Pattern $p -Replacement $r -Label 'L18 empty attendance row' -Expect 1

# ================================================================ 资料面板的失败分支（原来只给一句死胡同文案）
$p = @'
                } else {
                    console.error('Failed to load materials:', result.message);
                    document.getElementById('materials-grid').innerHTML = `
                        <div class="col-span-2 text-center py-10 text-slate-500">
                            <span class="material-symbols-outlined text-4xl mb-2">error</span>
                            <p>${result.message || 'Failed to load materials'}</p>
                        </div>
                    `;
                }
            } catch (error) {
                console.error('Error loading materials:', error);
                document.getElementById('materials-grid').innerHTML = `
                    <div class="col-span-2 text-center py-10 text-slate-500">
                        <span class="material-symbols-outlined text-4xl mb-2">error</span>
                        <p>Network error, please try again</p>
                    </div>
                `;
            }
'@
$r = @'
                } else {
                    console.error('Failed to load materials:', result.message);
                    panelError('materials-grid', 'Materials', 'loadMaterials(currentFolderId)', false, result.message);
                }
            } catch (error) {
                console.error('Error loading materials:', error);
                panelError('materials-grid', 'Materials', 'loadMaterials(currentFolderId)');
            }
'@
Edit-File -Pattern $p -Replacement $r -Label 'L19 materials failure -> panelError' -Literal -Expect 1

# ================================================================ 其余四个面板的 catch 只 console.error
$appends = @(
    @{ old = "console.error('Error loading announcements:', error);"; call = "                panelError('announcements-list', 'Announcements', 'loadAnnouncements()');" },
    @{ old = "console.error('Error loading assignments:', error);"; call = "                panelError('assignments-list', 'Assignments', 'loadAssignments()');" },
    @{ old = "console.error('Error loading attendance records:', error);"; call = "                panelError('attendance-records-body', 'Attendance records', 'loadAttendanceRecords()', true);" },
    @{ old = "console.error('Error loading quizzes:', error);"; call = "                panelError('quizzes-list', 'Quizzes', 'loadQuizzes()');" }
)
$i = 20
foreach ($a in $appends) {
    Edit-File -Pattern $a.old -Replacement ($a.old + "`n" + $a.call) -Label ("L" + $i + " catch " + $a.old) -Literal -Expect 1
    $i++
}

# ================================================================ 面板级失败提示 + 课程头重写
$helper = @'
        // 面板级失败提示：宁可说「没取到」，也不要留一片空白让人以为是「没有数据」。
        // reason 有值时用服务端原话（诚实），否则说明是网络/服务不可达。
        function panelError(containerId, what, retryExpr, rowMode, reason) {
            const box = document.getElementById(containerId);
            if (!box) { console.error('panelError: container missing:', containerId); return; }
            const detail = reason
                ? escapeHtml(String(reason))
                : 'The server did not answer. This is not an empty list.';
            const inner = '<span class="material-symbols-outlined text-3xl text-red-500">cloud_off</span>'
                + '<p class="font-bold text-slate-900 dark:text-slate-100 mt-2">' + escapeHtml(what) + ' could not be loaded</p>'
                + '<p class="text-sm nx-muted mt-1">' + detail + '</p>'
                + '<button onclick="' + retryExpr + '" class="nx-btn nx-btn-ghost mt-4">'
                + '<span class="material-symbols-outlined text-lg">refresh</span>Retry</button>';
            box.innerHTML = rowMode
                ? '<tr><td colspan="3" class="px-6 py-10"><div class="nx-empty">' + inner + '</div></td></tr>'
                : '<div class="nx-empty">' + inner + '</div>';
        }

'@
Edit-File -Pattern '        function renderItems(items, animate = true) {' -Replacement ($helper + '        function renderItems(items, animate = true) {') -Label 'L24 insert panelError helper' -Literal -Expect 1

$p = '(?s)async function loadCourseInfo\(\) \{.*?\}\s*// 加载资料列表'
$r = @'
async function loadCourseInfo() {
            const titleEl = document.getElementById('course-title');
            const metaEl = document.getElementById('course-meta');
            if (!titleEl || !metaEl) { console.error('course header elements are missing from the page'); return; }
            try {
                const response = await fetch(`/api/course/${courseId}`);
                const result = await response.json();

                if (result.code !== 200) {
                    console.error('Failed to load course:', result.message);
                    titleEl.textContent = 'Course unavailable';
                    metaEl.innerHTML = '<span class="flex items-center gap-1.5 text-red-600 dark:text-red-400">'
                        + '<span class="material-symbols-outlined text-lg">cloud_off</span>'
                        + escapeHtml(result.message || 'Course information is unavailable') + '</span>';
                    return;
                }

                const course = result.data || {};
                titleEl.textContent = course.name || 'Course';
                document.title = (course.name ? course.name + ' · ' : '') + 'CodeClass LMS';

                // 学分/学期以前是写死的 "6 Credit Hours" "Fall 2024"，现在用数据库里的真值；
                // 没填就不显示，而不是编一个。
                const bits = [];
                if (course.courseCode) bits.push(['tag', course.courseCode]);
                if (course.teacher && course.teacher.name) bits.push(['person', course.teacher.name]);
                bits.push(['group', (course.studentCount || 0) + ' Students']);
                if (course.creditHours) bits.push(['credit_card', course.creditHours + ' Credit Hours']);
                if (course.semester) bits.push(['calendar_today', course.semester]);
                metaEl.innerHTML = bits.map(function (b) {
                    return '<span class="flex items-center gap-1.5">'
                        + '<span class="material-symbols-outlined text-primary text-lg">' + b[0] + '</span>'
                        + escapeHtml(String(b[1])) + '</span>';
                }).join('');
            } catch (error) {
                console.error('Error loading course:', error);
                titleEl.textContent = 'Course unavailable';
                metaEl.innerHTML = '<span class="flex items-center gap-1.5 text-red-600 dark:text-red-400">'
                    + '<span class="material-symbols-outlined text-lg">cloud_off</span>'
                    + 'Course information could not be loaded.</span>';
            }
        }

        // 加载资料列表
'@
Edit-File -Pattern $p -Replacement $r -Label 'L25 loadCourseInfo rewrite' -Expect 1

# ================================================================
# 下面这一批是探针跑出来的真 bug：
#   探针把 /api/** 屏蔽掉之后，资料面板正确显示了「取不到 + Retry」，
#   但另外四个面板仍然显示旧的 error 文案，而且**没有 Retry**。
#   原因：这四处 catch 里 panelError(...) 之后还有一段旧的 innerHTML 赋值，
#   把 panelError 刚写好的内容又覆盖掉了。
#   同时它们的「服务端返回非 200」分支更糟：直接显示空状态
#   （"No assignments yet" / "No attendance records" / "No announcements yet"），
#   把「取不到数据」谎报成「本来就没有数据」。一并改成 panelError + 服务端原话。
# ================================================================

# L26 通知：服务端非 200 时不再冒充「没有通知」
# 这里用正则而不是字面量：L17 换出来的这段缩进和周围对不齐，逐字节比对太脆，用 \s* 吃掉差异。
# 仍是 Expect 1 —— 另一处同名空状态在 renderAnnouncements 里，没有 "} else {" 前缀。
$p = '(?s)\} else \{\s*container\.innerHTML = `\s*<div class="nx-empty">\s*<span class="material-symbols-outlined text-3xl nx-muted">campaign</span>\s*<p class="font-bold text-slate-900 dark:text-slate-100 mt-2">No announcements yet</p>\s*<p class="text-sm nx-muted mt-1">Nothing has been posted to this course\.</p>\s*</div>\s*`;\s*\}'
$r = @'
                } else {
                    panelError('announcements-list', 'Announcements', 'loadAnnouncements()', false, result.message);
                }
'@
Edit-File -Pattern $p -Replacement $r -Label 'L26 announcements server error -> panelError' -Expect 1

# L27 作业
$p = @'
                } else {
                    container.innerHTML = `
                        <div class="text-center py-12 text-slate-400">
                            <span class="material-symbols-outlined text-4xl mb-2">assignment</span>
                            <p>No assignments yet</p>
                        </div>
                    `;
                }
'@
$r = @'
                } else {
                    panelError('assignments-list', 'Assignments', 'loadAssignments()', false, result.message);
                }
'@
Edit-File -Pattern $p -Replacement $r -Label 'L27 assignments server error -> panelError' -Literal -Expect 1

# L28 签到记录
$p = @'
                } else {
                    tbody.innerHTML = `
                        <tr>
                            <td colspan="3" class="px-6 py-12 text-center text-slate-400">
                                <span class="material-symbols-outlined text-4xl mb-2">event_busy</span>
                                <p>No attendance records</p>
                            </td>
                        </tr>
                    `;
                }
'@
$r = @'
                } else {
                    panelError('attendance-records-body', 'Attendance records', 'loadAttendanceRecords()', true, result.message);
                }
'@
Edit-File -Pattern $p -Replacement $r -Label 'L28 attendance server error -> panelError' -Literal -Expect 1

# L29 测验
$p = @'
                } else {
                    container.innerHTML = `
                        <div class="text-center py-12 text-slate-400">
                            <span class="material-symbols-outlined text-4xl mb-2">quiz</span>
                            <p>${escapeHtml(result.message || 'Failed to load quizzes')}</p>
                        </div>
                    `;
                }
'@
$r = @'
                } else {
                    panelError('quizzes-list', 'Quizzes', 'loadQuizzes()', false, result.message);
                }
'@
Edit-File -Pattern $p -Replacement $r -Label 'L29 quizzes server error -> panelError' -Literal -Expect 1

# L30-L33 四处 catch：删掉覆盖 panelError 的旧 innerHTML
$p = @'
                panelError('announcements-list', 'Announcements', 'loadAnnouncements()');
                container.innerHTML = `
                    <div class="text-center py-12 text-slate-400">
                        <span class="material-symbols-outlined text-4xl mb-2">error</span>
                        <p>Failed to load announcements</p>
                    </div>
                `;
'@
Edit-File -Pattern $p -Replacement "                panelError('announcements-list', 'Announcements', 'loadAnnouncements()');" -Label 'L30 announcements catch: drop shadowing block' -Literal -Expect 1

$p = @'
                panelError('assignments-list', 'Assignments', 'loadAssignments()');
                container.innerHTML = `
                    <div class="text-center py-12 text-red-400">
                        <span class="material-symbols-outlined text-4xl mb-2">error</span>
                        <p>Failed to load assignments</p>
                    </div>
                `;
'@
Edit-File -Pattern $p -Replacement "                panelError('assignments-list', 'Assignments', 'loadAssignments()');" -Label 'L31 assignments catch: drop shadowing block' -Literal -Expect 1

$p = @'
                panelError('attendance-records-body', 'Attendance records', 'loadAttendanceRecords()', true);
                tbody.innerHTML = `
                    <tr>
                        <td colspan="3" class="px-6 py-12 text-center text-red-400">
                            <span class="material-symbols-outlined text-4xl mb-2">error</span>
                            <p>Failed to load records</p>
                        </td>
                    </tr>
                `;
'@
Edit-File -Pattern $p -Replacement "                panelError('attendance-records-body', 'Attendance records', 'loadAttendanceRecords()', true);" -Label 'L32 attendance catch: drop shadowing block' -Literal -Expect 1

$p = @'
                panelError('quizzes-list', 'Quizzes', 'loadQuizzes()');
                container.innerHTML = `
                    <div class="text-center py-12 text-red-400">
                        <span class="material-symbols-outlined text-4xl mb-2">error</span>
                        <p>Failed to load quizzes</p>
                    </div>
                `;
'@
Edit-File -Pattern $p -Replacement "                panelError('quizzes-list', 'Quizzes', 'loadQuizzes()');" -Label 'L33 quizzes catch: drop shadowing block' -Literal -Expect 1

# L34 公告时间：createTime 已被 @JsonIgnore，页面只拿得到 createTimeIso
Edit-File -Pattern 'const timeAgo = formatTimeAgo(a.createTime);' -Replacement 'const timeAgo = formatTimeAgo(a.createTimeIso || a.createTime);' -Label 'L34 announcement time uses createTimeIso' -Literal -Expect 1

# L35 顶栏：跟仪表盘统一成 nx-glass + nx-hairline-b；手机上原来 py-6 顶着 88px 高，压到 64px。
# 另外品牌方块原本是 onclick="window.location.reload()"（点一下把自己重载一遍），改成回仪表盘的链接；
# 右侧补一个 Dashboard 按钮，手机上不用再滚回去点面包屑返回。
$p = '(?s)<header class="flex items-center justify-between border-b border-primary/10 px-6 md:px-20 py-6 .*?</header>'
$r = @'
<header class="nx-glass nx-hairline-b sticky top-0 z-50 rounded-none border-0 flex items-center justify-between gap-4 px-4 py-3 md:px-8 md:py-3.5 lg:px-20">
                    <a class="flex items-center gap-3 text-slate-900 dark:text-slate-100" href="dashboard.html">
                        <span class="material-symbols-outlined text-3xl text-primary">terminal</span>
                        <h2 class="text-lg md:text-xl font-black leading-tight tracking-tight uppercase">CodeClass LMS</h2>
                    </a>
                    <a class="nx-btn nx-btn-ghost" href="dashboard.html"><span class="material-symbols-outlined text-lg">arrow_back</span>Dashboard</a>
                </header>
'@
Edit-File -Pattern $p -Replacement $r -Label 'L35 top bar: nx-glass + compact padding + dashboard link' -Expect 1


Write-Host ''
$script:log | ForEach-Object { Write-Host $_ }
Write-Host ''
Write-Host "failures: $script:fail"
if ($script:fail -gt 0) { exit 1 }
