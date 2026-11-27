# Vendored crates

Copies of two crates with small changes so they build for iOS. Used through `[patch.crates-io]`
in `../Cargo.toml`.

| Crate | Version | License | Change |
| --- | --- | --- | --- |
| `netwatch` ([n0-computer/net-tools](https://github.com/n0-computer/net-tools)) | 0.19.3 | MIT OR Apache-2.0 | Adds an iOS interfaces module (`src/interfaces/ios.rs`) and builds the network monitor on iOS. |
| `netdev` ([shellrow/netdev](https://github.com/shellrow/netdev)) | 0.46.3 | MIT | Limits the macOS-only IPv6 address-flag ioctl to macOS. |

Each crate keeps its own license; see the crate's `Cargo.toml` and license files.
