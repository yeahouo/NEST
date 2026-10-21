# Make three pages report load/action failures to the user instead of only console.error.
# All edits are count-asserted; a file is written ONLY if every edit for it matched exactly once.
$ErrorActionPreference = 'Stop'
$static = 'D:\project\NEST\src\main\resources\static'
$script:fail = 0
$script:log = New-Object System.Collections.Generic.List[string]

function Edit-File {
  param([string]$Name, [string]$Pattern, [string]$Replacement, [string]$Label, [int]$Expect = 1, [switch]$Literal)
  $p = Join-Path $static $Name
  $t = [System.IO.File]::ReadAllText($p)
  if ($Literal) {
    $n = 0; $idx = 0
    while (($idx = $t.IndexOf($Pattern, $idx)) -ge 0) { $n++; $idx += $Pattern.Length }
    if ($n -ne $Expect) { $script:fail++; $script:log.Add("FAIL  $Name : $Label (found $n, want $Expect)"); return }
    $t2 = $t.Replace($Pattern, $Replacement)
  } else {
    $n = ([regex]::Matches($t, $Pattern)).Count
    if ($n -ne $Expect) { $script:fail++; $script:log.Add("FAIL  $Name : $Label (matched $n, want $Expect)"); return }
    $t2 = [regex]::Replace($t, $Pattern, $Replacement)
  }
  [System.IO.File]::WriteAllText($p, $t2, [System.Text.UTF8Encoding]::new($false))
  $script:log.Add("ok    $Name : $Label")
}

# ============================================================
# 1) admin-invitation-codes.html  (already has showToast; loadCodes was silent)
# ============================================================
$a1 = @'
console.error('Failed to load codes:', result.message);
        renderCodesError(result.message || 'The server returned an error.');
'@
Edit-File -Name 'admin-invitation-codes.html' -Pattern "console\.error\('Failed to load codes:', result\.message\);" -Replacement $a1 -Label 'loadCodes non-200 -> visible'

$a2 = @'
console.error('Failed to load codes:', error);
      renderCodesError('Could not reach the server.');
'@
Edit-File -Name 'admin-invitation-codes.html' -Pattern "console\.error\('Failed to load codes:', error\);" -Replacement $a2 -Label 'loadCodes catch -> visible'

$a3 = @'
  // Visible failure state: an empty area must not be mistaken for "there are no codes".
  function renderCodesError(reason) {
    const container = document.getElementById('codes-container');
    if (!container) return;
    container.innerHTML = `
      <div class="col-span-3 p-12 text-center">
        <div class="h-16 w-16 mx-auto mb-4 rounded-md bg-red-500/10 flex items-center justify-center">
          <span class="material-symbols-outlined text-red-500 text-3xl">error</span>
        </div>
        <p class="font-bold mb-1">Invitation codes could not be loaded</p>
        <p class="text-sm text-slate-400 mb-2" id="codes-error-reason"></p>
        <p class="text-xs text-slate-400 mb-4">The empty area does not mean there are no codes.</p>
        <button onclick="loadCodes()" class="px-6 py-2 rounded-lg border border-slate-200 dark:border-slate-700 font-bold hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors">Retry</button>
      </div>
    `;
    const reasonEl = document.getElementById('codes-error-reason');
    if (reasonEl) reasonEl.textContent = reason;
  }

  function renderCodes(codes) {
'@
Edit-File -Name 'admin-invitation-codes.html' -Pattern '  function renderCodes\(codes\) \{' -Replacement $a3 -Label 'add renderCodesError'

# ============================================================
# 2) teacher-dashboard.html  (loadCourses + createCourse were silent)
# ============================================================
$b1 = @'
$1
        renderCoursesError(result.message || 'The server returned an error.');
'@
Edit-File -Name 'teacher-dashboard.html' -Pattern "(console\.warn\('.*Failed to load courses:', result\.message\);)" -Replacement $b1 -Label 'loadCourses non-200 -> visible'

$b2 = @'
$1
      renderCoursesError('Could not reach the server.');
'@
Edit-File -Name 'teacher-dashboard.html' -Pattern "(console\.error\('.*Failed to load courses:', error\);)" -Replacement $b2 -Label 'loadCourses catch -> visible'

$b3 = @'
  // Visible failure state: an empty grid must not be mistaken for "no courses".
  function renderCoursesError(reason) {
    const container = document.getElementById('courses-container');
    if (container) {
      container.innerHTML = `
        <div class="col-span-2 p-8 text-center">
          <div class="h-14 w-14 mx-auto mb-3 rounded-md bg-red-500/10 flex items-center justify-center">
            <span class="material-symbols-outlined text-red-500 text-3xl">error</span>
          </div>
          <p class="font-bold mb-1">Your courses could not be loaded</p>
          <p class="text-sm text-slate-400 mb-4" id="courses-error-reason"></p>
          <button onclick="loadCourses()" class="px-6 py-2 rounded-md border border-slate-200 dark:border-slate-700 font-bold hover:bg-slate-50 dark:hover:bg-slate-700 transition-colors">Retry</button>
        </div>
      `;
      const reasonEl = document.getElementById('courses-error-reason');
      if (reasonEl) reasonEl.textContent = reason;
    }
    // The counters are unknown right now, which is not the same as zero.
    const activeCoursesEl = document.getElementById('stat-active-courses');
    const totalStudentsEl = document.getElementById('stat-total-students');
    if (activeCoursesEl) activeCoursesEl.textContent = '\u2014';
    if (totalStudentsEl) totalStudentsEl.textContent = '\u2014';
  }

  function renderCourses(courses) {
'@
Edit-File -Name 'teacher-dashboard.html' -Pattern '  function renderCourses\(courses\) \{' -Replacement $b3 -Label 'add renderCoursesError'

$b4 = @'
          <div class="hidden mb-2 p-3 rounded-xl border border-red-200 dark:border-red-900/40 bg-red-50 dark:bg-red-900/10 text-sm text-red-600 dark:text-red-400" id="create-course-error"></div>
          <div class="flex gap-3 mt-2">
'@
Edit-File -Name 'teacher-dashboard.html' -Pattern '          <div class="flex gap-3 mt-2">' -Replacement $b4 -Label 'create-modal error slot'

$b5 = @'
    if (!name) {
      showCreateCourseError('Course name is required.');
'@
Edit-File -Name 'teacher-dashboard.html' -Pattern '    if \(!name\) \{' -Replacement $b5 -Label 'createCourse empty name -> visible'

$b6 = @'
        await loadCourses();
      } else {
        showCreateCourseError(result.message || 'Could not create the course.');
'@
Edit-File -Name 'teacher-dashboard.html' -Pattern '        await loadCourses\(\);' -Replacement $b6 -Label 'createCourse non-200 -> visible'

$b7 = @'
      console.error('Failed to create course:', error);
      showCreateCourseError('Could not reach the server.');
'@
Edit-File -Name 'teacher-dashboard.html' -Pattern "      console\.error\('Failed to create course:', error\);" -Replacement $b7 -Label 'createCourse catch -> visible'

$b8 = @'
  function showCreateCourseError(reason) {
    const el = document.getElementById('create-course-error');
    if (!el) return;
    el.textContent = reason;
    el.classList.remove('hidden');
  }

  async function createCourse() {
'@
Edit-File -Name 'teacher-dashboard.html' -Pattern '  async function createCourse\(\) \{' -Replacement $b8 -Label 'add showCreateCourseError'

$b9 = @'
  function openCreateModal() {
    const errorEl = document.getElementById('create-course-error');
    if (errorEl) errorEl.classList.add('hidden');
'@
Edit-File -Name 'teacher-dashboard.html' -Pattern '  function openCreateModal\(\) \{' -Replacement $b9 -Label 'clear create error on open'

Edit-File -Name 'teacher-dashboard.html' -Pattern 'id="stat-active-courses">0</p>' -Replacement ('id="stat-active-courses">' + [char]0x2014 + '</p>') -Label 'stat-active-courses default dash' -Literal
Edit-File -Name 'teacher-dashboard.html' -Pattern 'id="stat-total-students">0</p>' -Replacement ('id="stat-total-students">' + [char]0x2014 + '</p>') -Label 'stat-total-students default dash' -Literal

# ============================================================
# 3) dashboard.html  (loadCourses + joinClass were silent)
# ============================================================
$c1 = @'
        renderCourses(Array.isArray(courses) ? courses : []);
      } else {
        console.error('Failed to load courses:', result.message);
        renderCoursesError(result.message || 'The server returned an error.');
'@
Edit-File -Name 'dashboard.html' -Pattern '        renderCourses\(Array\.isArray\(courses\) \? courses : \[\]\);' -Replacement $c1 -Label 'loadCourses non-200 -> visible'

$c2 = @'
      console.error('Failed to load courses:', error);
      renderCoursesError('Could not reach the server.');
'@
Edit-File -Name 'dashboard.html' -Pattern "      console\.error\('Failed to load courses:', error\);" -Replacement $c2 -Label 'loadCourses catch -> visible'

$c3 = @'
  // Visible failure state: an empty grid must not be mistaken for "no courses".
  function renderCoursesError(reason) {
    const container = document.getElementById('courses-container');
    if (container) {
      container.innerHTML = `
        <div class="col-span-2 p-8 text-center">
          <div class="h-14 w-14 mx-auto mb-3 rounded-lg bg-red-500/10 flex items-center justify-center">
            <span class="material-symbols-outlined text-red-500 text-3xl">error</span>
          </div>
          <p class="font-bold mb-1">Your courses could not be loaded</p>
          <p class="text-sm text-slate-400 mb-4" id="courses-error-reason"></p>
          <button onclick="loadCourses()" class="px-6 py-2 rounded-lg border border-primary/20 bg-primary/5 text-primary font-bold hover:bg-primary/10 transition-colors">Retry</button>
        </div>
      `;
      const reasonEl = document.getElementById('courses-error-reason');
      if (reasonEl) reasonEl.textContent = reason;
    }
    const viewAll = document.getElementById('view-all-courses');
    if (viewAll) viewAll.style.display = 'none';
  }

  function renderCourses(courses) {
'@
Edit-File -Name 'dashboard.html' -Pattern '  function renderCourses\(courses\) \{' -Replacement $c3 -Label 'add renderCoursesError'

$c4 = @'
          <div class="hidden p-3 rounded-lg border border-red-200 dark:border-red-900/40 bg-red-50 dark:bg-red-900/10 text-sm text-red-600 dark:text-red-400" id="join-class-error"></div>
          <div class="flex flex-col gap-3 mt-2">
'@
Edit-File -Name 'dashboard.html' -Pattern '          <div class="flex flex-col gap-3 mt-2">' -Replacement $c4 -Label 'join-modal error slot'

$c5 = @'
  function openJoinModal() {
    const errorEl = document.getElementById('join-class-error');
    if (errorEl) errorEl.classList.add('hidden');
'@
Edit-File -Name 'dashboard.html' -Pattern '  function openJoinModal\(\) \{' -Replacement $c5 -Label 'clear join error on open'

$c6 = @'
    if (!code) {
      showJoinError('Please enter a class code.');
'@
Edit-File -Name 'dashboard.html' -Pattern '    if \(!code\) \{' -Replacement $c6 -Label 'joinClass empty code -> visible'

$c7 = @'
        document.getElementById('class-code').value = '';
      } else {
        showJoinError(result.message || 'Could not join this class.');
'@
Edit-File -Name 'dashboard.html' -Pattern "        document\.getElementById\('class-code'\)\.value = '';" -Replacement $c7 -Label 'joinClass non-200 -> visible'

$c8 = @'
      console.error('Failed to join class:', error);
      showJoinError('Could not reach the server.');
'@
Edit-File -Name 'dashboard.html' -Pattern "      console\.error\('Failed to join class:', error\);" -Replacement $c8 -Label 'joinClass catch -> visible'

$c9 = @'
  function showJoinError(reason) {
    const el = document.getElementById('join-class-error');
    if (!el) return;
    el.textContent = reason;
    el.classList.remove('hidden');
  }

  async function joinClass() {
'@
Edit-File -Name 'dashboard.html' -Pattern '  async function joinClass\(\) \{' -Replacement $c9 -Label 'add showJoinError'

$script:log | ForEach-Object { Write-Host $_ }
Write-Host ""
Write-Host "failures: $($script:fail)"
if ($script:fail -gt 0) { exit 1 }
