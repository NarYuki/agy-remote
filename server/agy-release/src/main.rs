// SPDX-License-Identifier: AGPL-3.0-only
//! Maintainer tool, never shipped to users: creates the release signing key and signs the update
//! manifest that `agy-connector` checks before it replaces itself (see docs/releasing-connector.md).
//!
//!     agy-release keygen <key file>            # once; prints the public key for update.rs
//!     agy-release sign <manifest.json> <key file>   # each release; prints the base64 signature
//!     agy-release sshsig <manifest.json> <key file> # each release; prints the same key's signature
//!                                                   # in OpenSSH's format, for `install.sh`

use std::path::Path;

use data_encoding::{BASE64, HEXLOWER};
use iroh::SecretKey;

fn keygen(out: &Path) -> Result<String, String> {
    if out.exists() {
        return Err(format!("{} already exists", out.display()));
    }
    let secret: [u8; 32] = rand::random();
    let mut opts = std::fs::OpenOptions::new();
    opts.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        opts.mode(0o600);
    }
    use std::io::Write;
    opts.open(out)
        .and_then(|mut f| f.write_all(HEXLOWER.encode(&secret).as_bytes()))
        .map_err(|e| format!("{}: {e}", out.display()))?;
    Ok(HEXLOWER.encode(SecretKey::from_bytes(&secret).public().as_bytes()))
}

fn sign(manifest: &Path, key_file: &Path) -> Result<String, String> {
    let bytes = std::fs::read(manifest).map_err(|e| format!("{}: {e}", manifest.display()))?;
    Ok(BASE64.encode(&read_key(key_file)?.sign(&bytes).to_bytes()))
}

fn read_key(key_file: &Path) -> Result<SecretKey, String> {
    let key_hex = std::fs::read_to_string(key_file).map_err(|e| format!("{}: {e}", key_file.display()))?;
    let key: [u8; 32] = HEXLOWER
        .decode(key_hex.trim().as_bytes())
        .ok()
        .and_then(|v| v.try_into().ok())
        .ok_or("the key file must hold a 64-character hex secret key")?;
    Ok(SecretKey::from_bytes(&key))
}

/// Namespace of the OpenSSH signature; `install.sh` asks `ssh-keygen -Y verify` for the same one.
const SSHSIG_NAMESPACE: &[u8] = b"agy-release";

/// An SSH wire-format string: 4-byte big-endian length, then the bytes.
fn ssh_string(out: &mut Vec<u8>, bytes: &[u8]) {
    out.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    out.extend_from_slice(bytes);
}

/// The manifest signed in OpenSSH's signature format (PROTOCOL.sshsig), with the release key.
/// `install.sh` checks it with `ssh-keygen -Y verify`, which every Mac and nearly every Linux
/// has; the raw signature from `sign` needs OpenSSL 3, which a stock Mac lacks.
fn sshsig(manifest: &Path, key_file: &Path) -> Result<String, String> {
    use sha2::{Digest, Sha512};
    let bytes = std::fs::read(manifest).map_err(|e| format!("{}: {e}", manifest.display()))?;
    let key = read_key(key_file)?;

    let mut signed = b"SSHSIG".to_vec();
    ssh_string(&mut signed, SSHSIG_NAMESPACE);
    ssh_string(&mut signed, b"");
    ssh_string(&mut signed, b"sha512");
    ssh_string(&mut signed, &Sha512::digest(&bytes));

    let mut public = Vec::new();
    ssh_string(&mut public, b"ssh-ed25519");
    ssh_string(&mut public, key.public().as_bytes());
    let mut signature = Vec::new();
    ssh_string(&mut signature, b"ssh-ed25519");
    ssh_string(&mut signature, &key.sign(&signed).to_bytes());

    let mut blob = b"SSHSIG".to_vec();
    blob.extend_from_slice(&1u32.to_be_bytes());
    ssh_string(&mut blob, &public);
    ssh_string(&mut blob, SSHSIG_NAMESPACE);
    ssh_string(&mut blob, b"");
    ssh_string(&mut blob, b"sha512");
    ssh_string(&mut blob, &signature);

    let body = BASE64.encode(&blob);
    let lines: Vec<&str> = body.as_bytes().chunks(70).map(|c| std::str::from_utf8(c).expect("base64 is ASCII")).collect();
    Ok(format!("-----BEGIN SSH SIGNATURE-----\n{}\n-----END SSH SIGNATURE-----", lines.join("\n")))
}

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let result = match args.iter().map(String::as_str).collect::<Vec<_>>().as_slice() {
        ["keygen", out] => keygen(Path::new(out)),
        ["sign", manifest, key] => sign(Path::new(manifest), Path::new(key)),
        ["sshsig", manifest, key] => sshsig(Path::new(manifest), Path::new(key)),
        _ => Err("usage: agy-release keygen <key file> | agy-release sign|sshsig <manifest.json> <key file>".into()),
    };
    match result {
        Ok(text) => println!("{text}"),
        Err(e) => {
            eprintln!("{e}");
            std::process::exit(1);
        }
    }
}
