#include <jni.h>
#include <android/log.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/uio.h>
#include <elf.h>
#include <dirent.h>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include <cstring>
#include <cstdlib>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "ShiroRun", __VA_ARGS__)

struct regs_arm64 { uint64_t r[31], sp, pc, pstate; };

bool read_mem(pid_t pid, uint64_t addr, void* out, size_t len) {
    iovec l{out, len}, r{(void*)addr, len};
    return process_vm_readv(pid, &l, 1, &r, 1, 0) == (ssize_t)len;
}
bool write_mem(pid_t pid, uint64_t addr, const void* in, size_t len) {
    iovec l{(void*)in, len}, r{(void*)addr, len};
    return process_vm_writev(pid, &l, 1, &r, 1, 0) == (ssize_t)len;
}

pid_t find_pid(const char* pkg) {
    DIR* d = opendir("/proc");
    if (!d) return -1;
    dirent* e;
    while ((e = readdir(d))) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        std::ifstream f(std::string("/proc/") + e->d_name + "/cmdline");
        std::string line;
        std::getline(f, line);
        if (line.rfind(pkg, 0) == 0) { pid_t p = atoi(e->d_name); closedir(d); return p; }
    }
    closedir(d);
    return -1;
}

uint64_t map_base(pid_t pid, const char* needle) {
    std::ifstream f("/proc/" + std::to_string(pid) + "/maps");
    std::string line;
    while (std::getline(f, line)) {
        if (line.find(needle) == std::string::npos) continue;
        auto dash = line.find('-');
        auto sp = line.find(' ');
        std::string off = line.substr(sp + 1, line.find(' ', sp + 1) - sp - 1);
        if (off == "00000000" || off == "0000000000000000")
            return strtoull(line.substr(0, dash).c_str(), nullptr, 16);
    }
    return 0;
}

bool resolve_elf(pid_t pid, uint64_t base, const char* name, uint64_t* out) {
    Elf64_Ehdr eh;
    if (!read_mem(pid, base, &eh, sizeof(eh))) return false;
    if (memcmp(eh.e_ident, ELFMAG, 4) != 0) return false;

    uint64_t dyn = 0;
    for (int i = 0; i < eh.e_phnum; i++) {
        Elf64_Phdr ph;
        if (!read_mem(pid, base + eh.e_phoff + i * sizeof(ph), &ph, sizeof(ph))) continue;
        if (ph.p_type == PT_DYNAMIC) { dyn = base + ph.p_vaddr; break; }
    }
    if (!dyn) return false;

    uint64_t symtab = 0, strtab = 0;
    for (int i = 0; i < 512; i++) {
        Elf64_Dyn d;
        if (!read_mem(pid, dyn + i * sizeof(d), &d, sizeof(d))) break;
        if (d.d_tag == DT_NULL) break;
        if (d.d_tag == DT_SYMTAB) symtab = base + d.d_un.d_ptr;
        if (d.d_tag == DT_STRTAB) strtab = base + d.d_un.d_ptr;
    }
    if (!symtab || !strtab || strtab <= symtab) return false;

    size_t n = (strtab - symtab) / sizeof(Elf64_Sym);
    for (size_t i = 0; i < n; i++) {
        Elf64_Sym s;
        if (!read_mem(pid, symtab + i * sizeof(s), &s, sizeof(s))) continue;
        if (!s.st_name || !s.st_value) continue;
        char nm[192] = {0};
        if (!read_mem(pid, strtab + s.st_name, nm, sizeof(nm) - 1)) continue;
        if (strcmp(nm, name) == 0) { *out = base + s.st_value; return true; }
    }
    return false;
}

uint64_t remote_call(pid_t pid, uint64_t fn, uint64_t a0, uint64_t a1, uint64_t a2) {
    regs_arm64 saved{}, r{};
    iovec io{&saved, sizeof(saved)};
    ptrace(PTRACE_GETREGSET, pid, (void*)NT_PRSTATUS, &io);
    memcpy(&r, &saved, sizeof(r));
    r.r[0] = a0; r.r[1] = a1; r.r[2] = a2; r.pc = fn; r.r[30] = 0;
    iovec io2{&r, sizeof(r)};
    ptrace(PTRACE_SETREGSET, pid, (void*)NT_PRSTATUS, &io2);

    int st;
    ptrace(PTRACE_CONT, pid, 0, 0);
    while (waitpid(pid, &st, 0) > 0) {
        if (!WIFSTOPPED(st)) break;
        siginfo_t si{};
        ptrace(PTRACE_GETSIGINFO, pid, 0, &si);
        if (si.si_signo == SIGTRAP) break;
        if (si.si_signo == SIGSEGV || si.si_signo == SIGILL || si.si_signo == SIGBUS) break;
        ptrace(PTRACE_CONT, pid, 0, 0);
    }
    iovec io3{&r, sizeof(r)};
    ptrace(PTRACE_GETREGSET, pid, (void*)NT_PRSTATUS, &io3);
    uint64_t ret = r.r[0];
    iovec io4{&saved, sizeof(saved)};
    ptrace(PTRACE_SETREGSET, pid, (void*)NT_PRSTATUS, &io4);
    return ret;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_shirorun_core_Injector_injectNative(JNIEnv* env, jclass, jstring jpkg, jstring jso) {
    const char* pkg = env->GetStringUTFChars(jpkg, nullptr);
    const char* lib = env->GetStringUTFChars(jso, nullptr);

    pid_t pid = find_pid(pkg);
    if (pid <= 0) { LOG("proceso no encontrado"); return JNI_FALSE; }

    if (ptrace(PTRACE_ATTACH, pid, 0, 0) < 0) { LOG("ptrace denegado"); return JNI_FALSE; }
    int st;
    waitpid(pid, &st, 0);

    regs_arm64 saved{};
    iovec io{&saved, sizeof(saved)};
    ptrace(PTRACE_GETREGSET, pid, (void*)NT_PRSTATUS, &io);

    uint64_t mem = remote_call(pid, saved.pc, 0, 0x2000, 3, 0x22);
    if (mem == 0 || mem == (uint64_t)-1) { ptrace(PTRACE_DETACH, pid, 0, 0); return JNI_FALSE; }
    write_mem(pid, mem, lib, strlen(lib) + 1);

    uint64_t linkbase = map_base(pid, "/linker64");
    uint64_t dlopen_fn = 0;
    if (!linkbase || !resolve_elf(pid, linkbase, "__loader_dlopen", &dlopen_fn)) {
        LOG("sin __loader_dlopen");
        ptrace(PTRACE_DETACH, pid, 0, 0);
        return JNI_FALSE;
    }

    remote_call(pid, dlopen_fn, mem, 2, 0);
    ptrace(PTRACE_DETACH, pid, 0, 0);

    env->ReleaseStringUTFChars(jpkg, pkg);
    env->ReleaseStringUTFChars(jso, lib);
    return JNI_TRUE;
}
