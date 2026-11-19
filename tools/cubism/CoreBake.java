import com.live2d.sdk.cubism.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public final class CoreBake {
    static void vector(DataOutputStream out, float[] values) throws IOException {
        for(float v : values) out.writeFloat(v);
    }
    public static void main(String[] args) throws Exception {
        Path mocFile = Path.of(args[0]);
        byte[] bytes = Files.readAllBytes(mocFile);
        System.out.println("Cubism Core "+Live2DCubismCore.getVersion()+" moc "+Live2DCubismCore.getMocVersion(bytes));
        if (!Live2DCubismCore.hasMocConsistency(bytes)) throw new IOException("Invalid moc3");
        try(CubismMoc moc = CubismMoc.instantiate(bytes); CubismModel model = moc.instantiateModel()) {
            model.update();
            CubismCanvasInfo canvas = model.getCanvasInfo();
            System.out.printf(Locale.ROOT,"Canvas %.1fx%.1f, %.2f ppu, %d artmeshes, %d offscreens%n",
                canvas.getSizeInPixels()[0],canvas.getSizeInPixels()[1],canvas.getPixelsPerUnit(),
                model.getDrawables().getCount(),model.getOffscreenRenderingViews().length);
            for(CubismParameterView p : model.getParameterViews()) {
                System.out.printf(Locale.ROOT,"%s %.3f %.3f %.3f%n",p.getId(),p.getMinimumValue(),p.getDefaultValue(),p.getMaximumValue());
            }
            for(CubismPartView part:model.getPartViews()) {
                System.out.printf(Locale.ROOT,"PART %s %.2f parent %d%n",part.getId(),part.getOpacity(),part.getParentPartIndex());
            }
            if(args.length==1 || (args.length==2 && args[1].equals("--movement"))) {
                if(args.length==1) return;
                for(CubismParameterView p:model.getParameterViews()) {
                    float value=p.getValue();
                    CubismDrawableView[] d=model.getDrawableViews();
                    float[][] neutral=new float[d.length][];
                    for(int i=0;i<d.length;i++) neutral[i]=d[i].getVertexPositions().clone();
                    float maximum=0;
                    for(float extreme:new float[]{p.getMinimumValue(),p.getMaximumValue()}){
                        p.setValue(extreme);model.update();
                        for(int i=0;i<d.length;i++) {
                            if(d[i].getOpacity()<0.001) continue;
                            float[] xy=d[i].getVertexPositions();
                            for(int j=0;j<xy.length;j+=2) maximum=Math.max(maximum,(float)Math.hypot(xy[j]-neutral[i][j],xy[j+1]-neutral[i][j+1]));
                        }
                    }
                    p.setValue(value); model.update();
                    if(maximum>.000001)System.out.printf(Locale.ROOT,"MOVES %s max %.3f canvas pixels%n",p.getId(), maximum*canvas.getPixelsPerUnit());
                }
                return;
            }
            Path outDir=Path.of(args[2]);
            Files.createDirectories(outDir);
            CubismDrawableView[] draws=model.getDrawableViews();
            if(model.getOffscreenRenderingViews().length!=0) throw new IOException("Offscreen groups need the official renderer");
            try(DataOutputStream s = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(outDir.resolve("topology.bin"))))) {
                vector(s,canvas.getSizeInPixels()); vector(s,canvas.getOriginInPixels()); s.writeFloat(canvas.getPixelsPerUnit());
                s.writeInt(draws.length);
                for(CubismDrawableView d:draws) {
                    s.writeUTF(d.getId());
                    s.writeInt(d.getTextureIndex()); s.writeInt(d.getConstantFlag()); s.writeInt(d.getBlendMode());
                    s.writeInt(d.getVertexCount()); vector(s,d.getVertexUvs());
                    s.writeInt(d.getIndices().length); for(short i:d.getIndices()) s.writeShort(i);
                    s.writeInt(d.getMasks().length); for(int i:d.getMasks()) s.writeInt(i);
                }
            }
            List<String> rows=Files.readAllLines(Path.of(args[1]));
            String[] ids=rows.remove(0).split("\t",-1);
            CubismParameterView[] params=new CubismParameterView[ids.length];
            CubismPartView[] parts = new CubismPartView[ids.length];
            for(int i=0;i<ids.length;i++) {
                if(ids[i].startsWith("part:")){
                    parts[i]=model.findPartView(ids[i].substring(5));
                    if(parts[i]==null) throw new IOException("Unknown Cubism part "+ids[i]);
                    continue;
                }
                params[i]=model.findParameterView(ids[i]);
                if(params[i]==null) throw new IOException("Unknown Cubism parameter "+ids[i]);
            }
            try(DataOutputStream s=new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(outDir.resolve("frames.bin"))))) {
                s.writeInt(rows.size());
                for(String row:rows) {
                    String[] columns=row.split("\t",-1);
                    if(columns.length!=params.length) throw new IOException("Parameter column count");
                    for(int j=0;j<columns.length;j++){
                        float value=Float.parseFloat(columns[j]);
                        if(parts[j]!=null){
                            if(!Float.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Part opacity outside [0,1]");
                            parts[j].setOpacity(value);
                            continue;
                        }
                        if(!Float.isFinite(value)||value<params[j].getMinimumValue()-.0001||value>params[j].getMaximumValue()+.0001)
                            throw new IllegalArgumentException(params[j].getId()+" out of range: "+value);
                        params[j].setValue(value);
                    }
                    model.update();
                    int[] renderOrders=model.getRenderOrders();
                    for(CubismDrawableView d:draws){
                        s.writeInt(renderOrders[d.getIndex()]);
                        s.writeFloat(d.getOpacity()); vector(s,d.getMultiplyColors()); vector(s,d.getScreenColors());
                        vector(s,d.getVertexPositions());
                    }
                    model.resetDrawableDynamicFlags();
                }
            }
        }
    }
}


