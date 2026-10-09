package dev.theredja.src2mc.bundle;

import java.util.Arrays;

/** Immutable, validated map index retained by a published generation. */
public record BundleMap(
    String mapId,
    String sourceName,
    int[] cellMin,
    int[] cellMax,
    int[] anchorCell,
    java.util.List<BundleMaterial> materials,
    java.util.List<BundleModel> models,
    java.util.List<BundleProp> props,
    boolean exceedsVanillaBuildHeight,
    SurfaceTable surfaces,
    java.util.Set<String> modelContentIds,
    AtlasIndex atlas,
    PropVisibility pvs,
    // Cells that block light without holding a block; null when the map has none.
    OcclusionTable occlusion,
    // Per-cell collision shapes; null when the map was exported without them.
    CollisionTable collision,
    // The map's sound; null when it was exported without any.
    AudioTable audio,
    // The map's entities for its logic; null when it was exported without them.
    LogicTable logic,
    // The map's moving entities, each Sable carries; null when the map has none.
    MoverTable movers,
    // The props the logic changes; null when there are none.
    LogicPropTable logicProps,
    // The faces the sky is drawn through and the 2D skybox; null when the map has no sky face.
    SkyTable sky,
    // The 3D skybox room; null without one.
    SkyboxTable skybox,
    // The map's baked light; null when it was exported without it.
    LightTable light,
    // The map's particle systems; null when it has none.
    ParticleTable particles,
    // The map's HDR flag and colour lookups; null when it was exported without them.
    LookTable look
) {
    public BundleMap(String mapId, String sourceName, int[] cellMin, int[] cellMax, int[] anchorCell,
                     java.util.List<BundleMaterial> materials, java.util.List<BundleModel> models,
                     java.util.List<BundleProp> props, boolean exceedsVanillaBuildHeight, SurfaceTable surfaces,
                     java.util.Set<String> modelContentIds, AtlasIndex atlas, PropVisibility pvs,
                     OcclusionTable occlusion, CollisionTable collision, AudioTable audio, LogicTable logic, MoverTable movers,
                     LogicPropTable logicProps, SkyTable sky, SkyboxTable skybox, LightTable light, ParticleTable particles) {
        this(mapId, sourceName, cellMin, cellMax, anchorCell, materials, models, props, exceedsVanillaBuildHeight,
            surfaces, modelContentIds, atlas, pvs, occlusion, collision, audio, logic, movers, logicProps, sky, skybox, light, particles, null);
    }

    public BundleMap(String mapId, String sourceName, int[] cellMin, int[] cellMax, int[] anchorCell,
                     java.util.List<BundleMaterial> materials, java.util.List<BundleModel> models,
                     java.util.List<BundleProp> props, boolean exceedsVanillaBuildHeight, SurfaceTable surfaces,
                     java.util.Set<String> modelContentIds, AtlasIndex atlas, PropVisibility pvs,
                     OcclusionTable occlusion, CollisionTable collision, AudioTable audio, LogicTable logic, MoverTable movers,
                     LogicPropTable logicProps, SkyTable sky, SkyboxTable skybox, LightTable light) {
        this(mapId, sourceName, cellMin, cellMax, anchorCell, materials, models, props, exceedsVanillaBuildHeight,
            surfaces, modelContentIds, atlas, pvs, occlusion, collision, audio, logic, movers, logicProps, sky, skybox, light, null, null);
    }

    public BundleMap(String mapId, String sourceName, int[] cellMin, int[] cellMax, int[] anchorCell,
                     java.util.List<BundleMaterial> materials, java.util.List<BundleModel> models,
                     java.util.List<BundleProp> props, boolean exceedsVanillaBuildHeight, SurfaceTable surfaces,
                     java.util.Set<String> modelContentIds, AtlasIndex atlas, PropVisibility pvs,
                     OcclusionTable occlusion, CollisionTable collision, AudioTable audio, LogicTable logic, MoverTable movers,
                     LogicPropTable logicProps, SkyTable sky, SkyboxTable skybox) {
        this(mapId, sourceName, cellMin, cellMax, anchorCell, materials, models, props, exceedsVanillaBuildHeight,
            surfaces, modelContentIds, atlas, pvs, occlusion, collision, audio, logic, movers, logicProps, sky, skybox, null);
    }

    public BundleMap(String mapId, String sourceName, int[] cellMin, int[] cellMax, int[] anchorCell,
                     java.util.List<BundleMaterial> materials, java.util.List<BundleModel> models,
                     java.util.List<BundleProp> props, boolean exceedsVanillaBuildHeight, SurfaceTable surfaces,
                     java.util.Set<String> modelContentIds, AtlasIndex atlas, PropVisibility pvs,
                     OcclusionTable occlusion, CollisionTable collision, AudioTable audio, LogicTable logic, MoverTable movers,
                     LogicPropTable logicProps, SkyTable sky) {
        this(mapId, sourceName, cellMin, cellMax, anchorCell, materials, models, props, exceedsVanillaBuildHeight,
            surfaces, modelContentIds, atlas, pvs, occlusion, collision, audio, logic, movers, logicProps, sky, null);
    }

    public BundleMap(String mapId, String sourceName, int[] cellMin, int[] cellMax, int[] anchorCell,
                     java.util.List<BundleMaterial> materials, java.util.List<BundleModel> models,
                     java.util.List<BundleProp> props, boolean exceedsVanillaBuildHeight, SurfaceTable surfaces,
                     java.util.Set<String> modelContentIds, AtlasIndex atlas, PropVisibility pvs,
                     OcclusionTable occlusion, CollisionTable collision, AudioTable audio, LogicTable logic, MoverTable movers,
                     LogicPropTable logicProps) {
        this(mapId, sourceName, cellMin, cellMax, anchorCell, materials, models, props, exceedsVanillaBuildHeight,
            surfaces, modelContentIds, atlas, pvs, occlusion, collision, audio, logic, movers, logicProps, null, null);
    }

    public BundleMap(String mapId, String sourceName, int[] cellMin, int[] cellMax, int[] anchorCell,
                     java.util.List<BundleMaterial> materials, java.util.List<BundleModel> models,
                     java.util.List<BundleProp> props, boolean exceedsVanillaBuildHeight, SurfaceTable surfaces,
                     java.util.Set<String> modelContentIds, AtlasIndex atlas, PropVisibility pvs,
                     OcclusionTable occlusion, CollisionTable collision, AudioTable audio, LogicTable logic, MoverTable movers) {
        this(mapId, sourceName, cellMin, cellMax, anchorCell, materials, models, props, exceedsVanillaBuildHeight,
            surfaces, modelContentIds, atlas, pvs, occlusion, collision, audio, logic, movers, null, null, null);
    }

    public BundleMap(String mapId, String sourceName, int[] cellMin, int[] cellMax, int[] anchorCell,
                     java.util.List<BundleMaterial> materials, java.util.List<BundleModel> models,
                     java.util.List<BundleProp> props, boolean exceedsVanillaBuildHeight, SurfaceTable surfaces,
                     java.util.Set<String> modelContentIds, AtlasIndex atlas, PropVisibility pvs,
                     OcclusionTable occlusion, CollisionTable collision, AudioTable audio, LogicTable logic) {
        this(mapId, sourceName, cellMin, cellMax, anchorCell, materials, models, props, exceedsVanillaBuildHeight,
            surfaces, modelContentIds, atlas, pvs, occlusion, collision, audio, logic, null, null, null, null);
    }

    public BundleMap(String mapId, String sourceName, int[] cellMin, int[] cellMax, int[] anchorCell,
                     java.util.List<BundleMaterial> materials, java.util.List<BundleModel> models,
                     java.util.List<BundleProp> props, boolean exceedsVanillaBuildHeight, SurfaceTable surfaces,
                     java.util.Set<String> modelContentIds, AtlasIndex atlas, PropVisibility pvs,
                     OcclusionTable occlusion, CollisionTable collision, AudioTable audio) {
        this(mapId, sourceName, cellMin, cellMax, anchorCell, materials, models, props, exceedsVanillaBuildHeight,
            surfaces, modelContentIds, atlas, pvs, occlusion, collision, audio, null, null, null, null, null);
    }

    public BundleMap(String mapId, String sourceName, int[] cellMin, int[] cellMax, int[] anchorCell,
                     java.util.List<BundleMaterial> materials, java.util.List<BundleModel> models,
                     java.util.List<BundleProp> props, boolean exceedsVanillaBuildHeight, SurfaceTable surfaces,
                     java.util.Set<String> modelContentIds, AtlasIndex atlas, PropVisibility pvs,
                     OcclusionTable occlusion, CollisionTable collision) {
        this(mapId, sourceName, cellMin, cellMax, anchorCell, materials, models, props, exceedsVanillaBuildHeight,
            surfaces, modelContentIds, atlas, pvs, occlusion, collision, null, null, null, null, null, null);
    }

    public BundleMap {
        cellMin = cellMin.clone();
        cellMax = cellMax.clone();
        anchorCell = anchorCell.clone();
        materials = java.util.List.copyOf(materials);
        models = java.util.List.copyOf(models);
        props = java.util.List.copyOf(props);
        modelContentIds = java.util.Set.copyOf(modelContentIds);
    }

    @Override public int[] cellMin() { return cellMin.clone(); }
    @Override public int[] cellMax() { return cellMax.clone(); }
    @Override public int[] anchorCell() { return anchorCell.clone(); }

    @Override
    public boolean equals(Object other) {
        return other instanceof BundleMap map
            && mapId.equals(map.mapId) && sourceName.equals(map.sourceName)
            && Arrays.equals(cellMin, map.cellMin) && Arrays.equals(cellMax, map.cellMax)
            && Arrays.equals(anchorCell, map.anchorCell)
            && materials.equals(map.materials) && models.equals(map.models) && props.equals(map.props)
            && exceedsVanillaBuildHeight == map.exceedsVanillaBuildHeight && surfaces.equals(map.surfaces)
            && modelContentIds.equals(map.modelContentIds) && java.util.Objects.equals(atlas, map.atlas)
            && pvs == map.pvs && occlusion == map.occlusion && collision == map.collision && audio == map.audio
            && logic == map.logic && movers == map.movers && logicProps == map.logicProps && sky == map.sky && skybox == map.skybox;
    }

    @Override
    public int hashCode() {
        int result = java.util.Objects.hash(mapId, sourceName, materials, models, props, exceedsVanillaBuildHeight);
        result = 31 * result + Arrays.hashCode(cellMin);
        result = 31 * result + Arrays.hashCode(cellMax);
        result = 31 * result + Arrays.hashCode(anchorCell);
        result = 31 * result + surfaces.hashCode();
        result = 31 * result + modelContentIds.hashCode();
        result = 31 * result + java.util.Objects.hashCode(atlas);
        result = 31 * result + System.identityHashCode(pvs);
        // Identity, like the visibility table: both are large and are never
        // rebuilt within one published generation, and this record is hashed
        // often enough that walking them would cost real frame time.
        result = 31 * result + System.identityHashCode(occlusion);
        result = 31 * result + System.identityHashCode(collision);
        result = 31 * result + System.identityHashCode(audio);
        result = 31 * result + System.identityHashCode(logic);
        result = 31 * result + System.identityHashCode(movers);
        result = 31 * result + System.identityHashCode(logicProps);
        result = 31 * result + System.identityHashCode(sky);
        return 31 * result + System.identityHashCode(skybox);
    }

    public int materialCount() { return materials.size(); }
    public int modelCount() { return models.size(); }
}
