#ifndef METALRENDER_FAMILIES_LAYOUT_H
#define METALRENDER_FAMILIES_LAYOUT_H

#define MR_SLOT_VERTEX 0
#define MR_SLOT_PROJECTION 16
#define MR_SLOT_GLOBALS 17
#define MR_SLOT_FOG 18
#define MR_SLOT_DYNAMIC_TRANSFORMS 19
#define MR_SLOT_LIGHTING 20
#define MR_SLOT_CHUNK_SECTION 21
#define MR_SLOT_LIGHTMAP_INFO 22
#define MR_SLOT_SPRITE_ANIMATION_INFO 23
#define MR_SLOT_CLOUD_INFO 24
#define MR_SLOT_SAMPLER_INFO 25
#define MR_SLOT_POST_CONFIG 26
#define MR_SLOT_ROTSCALE_CONFIG 27
#define MR_SLOT_CLOUD_FACES 28
#define MR_SLOT_TERRAIN_UNIFORM 29

#define MR_TEX_CUBE 15

#define MR_FC_HAS_BLEND 0
#define MR_FC_ALPHA_CUTOUT 1
#define MR_FC_NO_OVERLAY 2
#define MR_FC_EMISSIVE 3
#define MR_FC_PER_FACE_LIGHTING 4
#define MR_FC_NO_CARDINAL_LIGHTING 5
#define MR_FC_APPLY_TEXTURE_MATRIX 6
#define MR_FC_DISSOLVE 7
#define MR_FC_PORTAL_LAYERS 8
#define MR_FC_VS_VARIANT 9
#define MR_FC_FS_VARIANT 10
#define MR_FC_HAS_COLOR 11
#define MR_FC_HAS_UV0 12
#define MR_FC_HAS_UV1 13
#define MR_FC_HAS_UV2 14
#define MR_FC_HAS_NORMAL 15
#define MR_FC_HAS_LINE_WIDTH 16
#define MR_FC_HAS_POSITION 17
#define MR_FC_HAS_PROJECTION 18
#define MR_FC_HAS_DYNAMIC_TRANSFORMS 19
#define MR_FC_GLINT 20
#define MR_FC_GLINT_SPECIAL 21
#define MR_FC_HAS_UV3 22

#define MR_VS_GUI 0
#define MR_VS_POSITION_TEX_COLOR 1
#define MR_VS_PANORAMA 2
#define MR_VS_TEXT 10
#define MR_VS_TEXT_BACKGROUND 11
#define MR_VS_TEXT_INTENSITY 12
#define MR_VS_TEXT_SEE_THROUGH 13
#define MR_VS_TEXT_BACKGROUND_SEE_THROUGH 14
#define MR_VS_TEXT_INTENSITY_SEE_THROUGH 15
#define MR_VS_SKY 20
#define MR_VS_STARS 21
#define MR_VS_POSITION_TEX 22
#define MR_VS_POSITION_COLOR 23
#define MR_VS_LINES 30
#define MR_VS_DEBUG_POINT 31
#define MR_VS_SCREENQUAD 40
#define MR_VS_ROTSCALE 41
#define MR_VS_ANIMATE_SPRITE 50
#define MR_VS_TERRAIN 70
#define MR_VS_BLOCK 71
#define MR_VS_CRUMBLING 72
#define MR_VS_ENTITY 80
#define MR_VS_ITEM 81
#define MR_VS_GLINT 82
#define MR_VS_POSITION 83
#define MR_VS_BEACON_BEAM 84
#define MR_VS_END_PORTAL 85
#define MR_VS_ENTITY_SHADOW 86
#define MR_VS_LEASH 87
#define MR_VS_LIGHTNING 88
#define MR_VS_OUTLINE 89
#define MR_VS_WATER_MASK 90
#define MR_VS_PARTICLE 100
#define MR_VS_CLOUDS 110
#define MR_VS_WORLD_BORDER 111

#define MR_FS_GUI 0
#define MR_FS_POSITION_TEX_COLOR 1
#define MR_FS_PANORAMA 2
#define MR_FS_GLASS 3
#define MR_FS_GLASS_BLUR 4
#define MR_FS_TEXT 10
#define MR_FS_TEXT_BACKGROUND 11
#define MR_FS_TEXT_INTENSITY 12
#define MR_FS_TEXT_SEE_THROUGH 13
#define MR_FS_TEXT_BACKGROUND_SEE_THROUGH 14
#define MR_FS_TEXT_INTENSITY_SEE_THROUGH 15
#define MR_FS_SKY 20
#define MR_FS_STARS 21
#define MR_FS_POSITION_TEX 22
#define MR_FS_POSITION_COLOR 23
#define MR_FS_LINES 30
#define MR_FS_BLIT_SCREEN 40
#define MR_FS_LIGHTMAP 41
#define MR_FS_POST_BLIT 42
#define MR_FS_BOX_BLUR 43
#define MR_FS_COLOR_CONVOLVE 44
#define MR_FS_BITS 45
#define MR_FS_ENTITY_SOBEL 46
#define MR_FS_ENTITY_OUTLINE_BOX_BLUR 47
#define MR_FS_INVERT 48
#define MR_FS_SPIDERCLIP 49
#define MR_FS_TRANSPARENCY 50
#define MR_FS_ANIMATE_SPRITE_BLIT 60
#define MR_FS_ANIMATE_SPRITE_INTERPOLATE 61
#define MR_FS_TERRAIN 70
#define MR_FS_BLOCK 71
#define MR_FS_CRUMBLING 72
#define MR_FS_ENTITY 80
#define MR_FS_ITEM 81
#define MR_FS_GLINT 82
#define MR_FS_POSITION 83
#define MR_FS_BEACON_BEAM 84
#define MR_FS_END_PORTAL 85
#define MR_FS_ENTITY_SHADOW 86
#define MR_FS_LEASH 87
#define MR_FS_LIGHTNING 88
#define MR_FS_OUTLINE 89
#define MR_FS_WATER_MASK 90
#define MR_FS_PARTICLE 100
#define MR_FS_CLOUDS 110
#define MR_FS_WORLD_BORDER 111

#define MR_BLOCK_PROJECTION 0
#define MR_BLOCK_DYNAMIC_TRANSFORMS 1
#define MR_BLOCK_FOG 2
#define MR_BLOCK_GLOBALS 3
#define MR_BLOCK_LIGHTING 4
#define MR_BLOCK_CHUNK_SECTION 5
#define MR_BLOCK_LIGHTMAP_INFO 6
#define MR_BLOCK_SPRITE_ANIMATION_INFO 7
#define MR_BLOCK_SAMPLER_INFO 8
#define MR_BLOCK_BLUR_CONFIG 9
#define MR_BLOCK_BLIT_CONFIG 10
#define MR_BLOCK_COLOR_CONFIG 11
#define MR_BLOCK_BITS_CONFIG 12
#define MR_BLOCK_INVERT_CONFIG 13
#define MR_BLOCK_ROTSCALE_CONFIG 14
#define MR_BLOCK_SPIDER_CONFIG 15
#define MR_BLOCK_CLOUD_INFO 16
#define MR_BLOCK_TERRAIN_UNIFORM 17
#define MR_BLOCK_COUNT 18

struct MRProjection {
  float ProjMat[16];
};

struct MRDynamicTransforms {
  float ModelViewMat[16];
  float TextureMat[16];
  float ColorModulator[4];
  float ModelOffset[3];
};

struct MRFog {
  float FogColor[4];
  float FogEnvironmentalStart;
  float FogEnvironmentalEnd;
  float FogRenderDistanceStart;
  float FogRenderDistanceEnd;
  float FogSkyEnd;
  float FogCloudsEnd;
};

struct MRGlobals {
  int CameraBlockPos[3];
  float GlintAlpha;
  float CameraOffset[3];
  float GameTime;
  float ScreenSize[2];
  int MenuBlurRadius;
  int UseRgss;
};

struct MRLighting {
  float Light0_Direction[3];
  float _pad0;
  float Light1_Direction[3];
};

struct MRChunkSection {
  int ChunkPosition[3];
  float ChunkVisibility;
};

struct MRTerrainUniform {
  float ModelViewMat[16];
  int TextureSize[2];
};

struct MRLightmapInfo {
  float SkyFactor;
  float BlockFactor;
  float NightVisionFactor;
  float DarknessScale;
  float BossOverlayWorldDarkeningFactor;
  float BrightnessFactor;
  float _pad0[2];
  float BlockLightTint[3];
  float _pad1;
  float SkyLightColor[3];
  float _pad2;
  float AmbientColor[3];
  float _pad3;
  float NightVisionColor[3];
};

struct MRSpriteAnimationInfo {
  float ProjectionMatrix[16];
  float SpriteMatrix[16];
  float UPadding;
  float VPadding;
  int MipMapLevel;
};

struct MRSamplerInfo {
  float OutSize[2];
  float InSize[2];
};

struct MRBlurConfig {
  float BlurDir[2];
  float Radius;
};

struct MRBlitConfig {
  float ColorModulate[4];
};

struct MRColorConfig {
  float RedMatrix[3];
  float _pad0;
  float GreenMatrix[3];
  float _pad1;
  float BlueMatrix[3];
};

struct MRBitsConfig {
  float Resolution;
  float MosaicSize;
};

struct MRInvertConfig {
  float InverseAmount;
};

struct MRRotScaleConfig {
  float InScale[2];
  float InOffset[2];
  float InRotation;
};

struct MRCloudInfo {
  float CloudColor[4];
  float CloudOffset[3];
  float _pad0;
  float CellSize[3];
};

struct MRSpiderConfig {
  float Scissor[4];
  float Vignette[4];
};

#endif
