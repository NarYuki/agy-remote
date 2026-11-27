use cfg_aliases::cfg_aliases;

fn main() {
    cfg_aliases! {
        wasm_browser: { all(target_family = "wasm", target_os = "unknown") },
        posix_minimal: { target_os = "espidf" },
        netdev: { not(any(target_os = "espidf", all(target_family = "wasm", target_os = "unknown"))) },
        bsd: { any(target_os = "freebsd", target_os = "openbsd", target_os = "netbsd", target_os = "macos") },
    }
}
