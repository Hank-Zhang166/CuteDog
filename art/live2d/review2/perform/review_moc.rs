use ayatsuri_format::model::Model;
use ayatsuri_import::{to_moc3, ExportVersion};
use std::{env, fs, path::PathBuf};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let folder = PathBuf::from(env::args_os().nth(1).ok_or("missing source directory")?);
    let model: Model = serde_json::from_slice(&fs::read(folder.join("rig.json"))?)?;
    fs::write(folder.join("CuteDog.moc3"), to_moc3(&model, ExportVersion::V5_00)?)?;
    Ok(())
}
