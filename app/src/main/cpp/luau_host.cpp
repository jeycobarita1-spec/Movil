#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <dirent.h>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include <thread>
#include <chrono>
#include <cstring>
#include <cstdlib>
#include <unistd.h>
#include <sys/stat.h>
#include "lua.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "ShiroRun", __VA_ARGS__)
#define DIRBASE "/data/local/tmp/shirorun"

typedef lua_State* (*newthread_t)(lua_State*);
typedef int  (*loadbuffer_t)(lua_State*, const char*, size_t, const char*);
typedef int  (*resume_t)(lua_State*, lua_State**, int);
typedef int  (*gettop_t)(lua_State*);
typedef void (*pop_t)(lua_State*, int);
typedef lua_State* (*newstate_t)();

static newthread_t  p_newthread = nullptr;
static loadbuffer_t p_loadbuffer = nullptr;
static resume_t     p_resume = nullptr;
static gettop_t     p_gettop = nullptr;
static pop_t        p_pop = nullptr;
static lua_State*   g_state = nullptr;

static void* sym(const char* n) {
    void* p = dlsym(RTLD_DEFAULT, n);
    if (p) return p;
    void* h = dlopen("libroblox.so", RTLD_NOW);
    if (!h) h = dlopen("librobloxlua.so", RTLD_NOW);
    return h ? dlsym(h, n) : nullptr;
}

static bool in_rw(uint64_t a) {
    std::ifstream f("/proc/self/maps");
    std::string l;
    while (std::getline(f, l)) {
        auto d = l.find('-');
        uint64_t s = strtoull(l.substr(0, d).c_str(), nullptr, 16);
        uint64_t e = strtoull(l.substr(d + 1, l.find(' ') - d - 1).c_str(), nullptr, 16);
        if (a >= s && a < e) return l.find("rw") != std::string::npos;
    }
    return false;
}

// heuristica: busca un lua_State por su cabecera (global_State en +0x10 y 3 punteros de pila ascendentes)
static lua_State* find_state() {
    std::ifstream f("/proc/self/maps");
    std::string l;
    std::vector<std::pair<uint64_t, uint64_t>> rw;
    while (std::getline(f, l)) {
        if (l.find("rw") == std::string::npos) continue;
        auto d = l.find('-');
        uint64_t s = strtoull(l.substr(0, d).c_str(), nullptr, 16);
        uint64_t e = strtoull(l.substr(d + 1, l.find(' ') - d - 1).c_str(), nullptr, 16);
        if (e - s > 0x20000000) continue;
        rw.push_back({s, e});
    }
    for (auto& r : rw) {
        for (uint64_t a = r.first; a + 0x60 < r.second; a += 8) {
            uint64_t g = *(uint64_t*)(a + 0x10);
            if (!in_rw(g)) continue;
            uint64_t t1 = *(uint64_t*)(a + 0x28);
            uint64_t t2 = *(uint64_t*)(a + 0x30);
            uint64_t t3 = *(uint64_t*)(a + 0x38);
            if (!in_rw(t1) || !in_rw(t2) || !in_rw(t3)) continue;
            if (t1 <= t2 && t2 <= t3) return (lua_State*)a;
        }
    }
    return nullptr;
}

static int run_file(const std::string& path) {
    if (!g_state) g_state = find_state();
    if (!g_state) return -1;

    std::ifstream f(path);
    std::string src((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
    if (src.empty()) return -2;

    lua_State* co = p_newthread(g_state);
    p_pop(g_state, 1);
    int base = p_gettop(co);

    if (p_loadbuffer(co, src.c_str(), src.size(), path.c_str()) != 0) return -3;
    return p_resume(co, nullptr, base);
}

static void watch() {
    for (int i = 0; i < 120; i++) {
        if (sym("lua_newthread")) break;
        std::this_thread::sleep_for(std::chrono::milliseconds(500));
    }
    p_newthread  = (newthread_t)sym("lua_newthread");
    p_loadbuffer = (loadbuffer_t)sym("luaL_loadbuffer");
    p_resume     = (resume_t)sym("lua_resume");
    p_gettop     = (gettop_t)sym("lua_gettop");
    p_pop        = (pop_t)sym("lua_pop");
    if (!p_newthread || !p_loadbuffer || !p_resume) { LOG("simbolos Luau no resueltos"); return; }

    mkdir(DIRBASE, 0777);
    mkdir(DIRBASE "/queue", 0777);
    mkdir(DIRBASE "/done", 0777);

    std::string boot = std::string(DIRBASE) + "/boot.lua";
    if (access(boot.c_str(), F_OK) == 0) run_file(boot);

    for (;;) {
        DIR* d = opendir(DIRBASE "/queue");
        if (!d) { std::this_thread::sleep_for(std::chrono::seconds(1)); continue; }
        dirent* e;
        while ((e = readdir(d))) {
            std::string n = e->d_name;
            if (n.size() < 5 || n.substr(n.size() - 4) != ".lua") continue;
            std::string full = std::string(DIRBASE "/queue/") + n;
            int r = run_file(full);
            rename(full.c_str(), (std::string(DIRBASE "/done/") + n).c_str());
            std::ofstream(std::string(DIRBASE "/done/") + n + ".rc") << r;
        }
        closedir(d);
        std::this_thread::sleep_for(std::chrono::milliseconds(250));
    }
}

__attribute__((constructor)) static void boot() {
    std::thread(watch).detach();
}
