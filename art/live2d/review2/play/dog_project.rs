use ayatsuri_format::model::Model;
use ayatsuri_import::{to_cmo3, to_moc3, Cmo3Export, ExportVersion};
use std::{env, fs, path::PathBuf};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let dir = PathBuf::from(env::args_os().nth(1).ok_or("missing project directory")?);
    let model: Model = serde_json::from_slice(&fs::read(dir.join("rig.json"))?)?;
    let paths: Vec<String> = serde_json::from_slice(&fs::read(dir.join("textures.json"))?)?;
    let textures: Vec<Vec<u8>> = paths.iter().map(|p| fs::read(dir.join(p))).collect::<Result<_,_>>()?;
    let labels: Vec<String> = model.parameters.iter().map(|p| p.id.to_string()).collect();
    let cmo = to_cmo3(&Cmo3Export { name: "CuteDog", model: &model, textures: &textures,
        param_names: &labels, part_names: &[], mesh_names: &[], deformer_names: &[],
        param_groups: &[("Dog performance".into(),(0..labels.len() as u32).collect())] })?;
    fs::write(dir.join("CuteDog.cmo3"), cmo)?;
    fs::write(dir.join("CuteDog.moc3"), to_moc3(&model,ExportVersion::V5_00)?)?;
    println!("{} drawings, {} warp deformers",model.art_meshes.len(),model.deformers.len());
    Ok(())
}
