/*
 * m65.CMD.exe: runs "m65.exe CMD ARGS..." from the same directory (part of m65fs, GPL-3).
 *
 * Windows has no exec(): this waits for m65.exe and passes on its exit status. Unlike a .cmd
 * wrapper, Ctrl-C asks no "Terminate batch job (Y/N)?" (m65.exe gets the Ctrl-C and ends
 * cleanly; the launcher ignores it), and the console is switched to UTF-8, which m65.exe
 * writes, and back to its code page afterwards. One binary for all commands: the command
 * comes from its own file name, so m65.ls.exe, m65.cp.exe, ... are copies of it.
 *
 * No C runtime, only kernel32: a few KB.
 */
#include <stddef.h> /* wchar_t (freestanding: no C runtime needed) */

typedef int BOOL;
typedef unsigned int UINT, DWORD;
typedef wchar_t WCHAR;
typedef void *HANDLE;

typedef struct {
  DWORD cb;
  WCHAR *reserved, *desktop, *title;
  DWORD x, y, xSize, ySize, xChars, yChars, fill, flags;
  unsigned short showWindow, reserved2;
  void *reserved3;
  HANDLE stdIn, stdOut, stdErr;
} STARTUPINFOW;

typedef struct {
  HANDLE process, thread;
  DWORD processId, threadId;
} PROCESS_INFORMATION;

__declspec(dllimport) DWORD __stdcall GetModuleFileNameW(HANDLE, WCHAR *, DWORD);
__declspec(dllimport) WCHAR *__stdcall GetCommandLineW(void);
__declspec(dllimport) void __stdcall GetStartupInfoW(STARTUPINFOW *);
__declspec(dllimport) BOOL __stdcall CreateProcessW(const WCHAR *, WCHAR *, void *, void *, BOOL, DWORD,
                                                    void *, const WCHAR *, STARTUPINFOW *,
                                                    PROCESS_INFORMATION *);
__declspec(dllimport) DWORD __stdcall WaitForSingleObject(HANDLE, DWORD);
__declspec(dllimport) BOOL __stdcall GetExitCodeProcess(HANDLE, DWORD *);
__declspec(dllimport) UINT __stdcall GetConsoleOutputCP(void);
__declspec(dllimport) BOOL __stdcall SetConsoleOutputCP(UINT);
__declspec(dllimport) BOOL __stdcall SetConsoleCtrlHandler(BOOL(__stdcall *)(DWORD), BOOL);
__declspec(dllimport) void __stdcall ExitProcess(UINT);

#define PATH 32768
static WCHAR self[PATH];          /* C:\...\bin\m65.ls.exe */
static WCHAR line[PATH + 64];     /* "C:\...\bin\m65.exe" ls ARGS... */

/* Ctrl-C: handled (ignored) here; m65.exe, in the same console, gets it too and ends itself.
   A handler, not SetConsoleCtrlHandler(NULL, TRUE): that would be inherited by m65.exe. */
static BOOL __stdcall ignore(DWORD type) { (void) type; return 1; }

static WCHAR *put(WCHAR *d, const WCHAR *s, const WCHAR *end) {
  while (s < end) *d++ = *s++;
  return d;
}

void start(void) {
  DWORD n = GetModuleFileNameW(0, self, PATH);
  if (n == 0 || n >= PATH) ExitProcess(2);
  WCHAR *base = self;                               /* file name after the last \ */
  for (WCHAR *p = self; *p; p++) if (*p == '\\' || *p == '/') base = p + 1;
  WCHAR *dot = 0;                                   /* the ".exe" */
  for (WCHAR *p = base; *p; p++) if (*p == '.') dot = p;
  WCHAR *cmd = base + 4;                            /* after "m65." */
  if (dot == 0 || dot <= cmd) ExitProcess(2);

  /* "DIR\m65.exe" CMD, then the arguments as typed (the command line after our own name) */
  static const WCHAR exe[] = L"m65.exe";
  WCHAR *d = line;
  *d++ = '"';
  d = put(d, self, base);
  d = put(d, exe, exe + 7);
  *d++ = '"';
  *d++ = ' ';
  d = put(d, cmd, dot);
  WCHAR *a = GetCommandLineW();
  if (*a == '"') { for (a++; *a && *a != '"'; a++) {} if (*a) a++; }
  else { while (*a && *a != ' ' && *a != '\t') a++; }
  for (const WCHAR *e = a; *e; e++) if (d < line + PATH + 63) *d++ = *e;
  *d = 0;

  SetConsoleCtrlHandler(ignore, 1);                 /* first: no Ctrl-C between switch and restore */
  UINT cp = GetConsoleOutputCP();                   /* 0: no console (output redirected) */
  if (cp) SetConsoleOutputCP(65001);

  STARTUPINFOW si;
  PROCESS_INFORMATION pi;
  GetStartupInfoW(&si);
  DWORD status = 2;
  if (CreateProcessW(0, line, 0, 0, 1, 0, 0, 0, &si, &pi)) {
    WaitForSingleObject(pi.process, 0xFFFFFFFF);
    GetExitCodeProcess(pi.process, &status);
  }
  if (cp) SetConsoleOutputCP(cp);
  ExitProcess(status);
}
