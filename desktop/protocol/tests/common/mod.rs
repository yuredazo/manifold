#![allow(dead_code)]

use std::collections::HashMap;
use std::path::PathBuf;

pub fn fixture(name: &str) -> String {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../fixtures").join(name);
    std::fs::read_to_string(&path).unwrap_or_else(|_| panic!("missing fixture {}", path.display())).replace("\r\n", "\n")
}

pub fn compat() -> HashMap<String, String> {
    fixture("compat.txt")
        .lines()
        .filter(|line| !line.trim().is_empty())
        .map(|line| {
            let (key, value) = line.split_once('=').expect("key=value");
            (key.to_string(), value.to_string())
        })
        .collect()
}
