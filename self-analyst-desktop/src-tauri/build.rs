fn main() {
    let root = std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../self-analyst-app/src/main/resources/i18n");
    let registry_path = root
        .join("languages.json")
        .canonicalize()
        .expect("language registry");
    println!("cargo:rerun-if-changed={}", root.display());
    let registry = std::fs::read_to_string(&registry_path).expect("read language registry");
    let languages: serde_json::Value =
        serde_json::from_str(&registry).expect("parse language registry");
    let mut generated = format!(
        "const REGISTRY: &str = include_str!({:?});\nconst CATALOGS: &[(&str, &str)] = &[\n",
        registry_path
    );
    for language in languages.as_array().expect("language list") {
        let code = language["code"].as_str().expect("language code");
        let resource = language["resource"].as_str().expect("language resource");
        assert!(resource.chars().all(|c| c.is_ascii_lowercase() || c == '-'));
        let path = root
            .join("native")
            .join(format!("{resource}.json"))
            .canonicalize()
            .expect("native catalog");
        generated.push_str(&format!("({code:?}, include_str!({path:?})),\n"));
    }
    generated.push_str("];\n");
    std::fs::write(
        std::path::Path::new(&std::env::var("OUT_DIR").unwrap()).join("languages.rs"),
        generated,
    )
    .expect("embed language resources");
    tauri_build::build()
}
