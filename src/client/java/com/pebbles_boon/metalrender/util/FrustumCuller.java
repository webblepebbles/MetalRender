package com.pebbles_boon.metalrender.util;
import org.joml.Matrix4f;
public class FrustumCuller {
  public enum MovementState { STANDING, WALKING, FLYING }
  private final float[][] planes = new float[6][4];
  private boolean valid;
  public void update(Matrix4f viewProjection) {
    if (viewProjection == null) {
      valid = false;
      return;
    }
    extractPlanes(viewProjection);
    valid = true;
  }
  private void extractPlanes(Matrix4f m) {
    setPlane(0, m.m30() + m.m00(), m.m31() + m.m01(), m.m32() + m.m02(),
             m.m33() + m.m03());
    setPlane(1, m.m30() - m.m00(), m.m31() - m.m01(), m.m32() - m.m02(),
             m.m33() - m.m03());
    setPlane(2, m.m30() + m.m10(), m.m31() + m.m11(), m.m32() + m.m12(),
             m.m33() + m.m13());
    setPlane(3, m.m30() - m.m10(), m.m31() - m.m11(), m.m32() - m.m12(),
             m.m33() - m.m13());
    setPlane(4, m.m30() + m.m20(), m.m31() + m.m21(), m.m32() + m.m22(),
             m.m33() + m.m23());
    setPlane(5, m.m30() - m.m20(), m.m31() - m.m21(), m.m32() - m.m22(),
             m.m33() - m.m23());
    for (int i = 0; i < 6; i++)
      normalizePlane(i);
  }
  private void setPlane(int idx, float a, float b, float c, float d) {
    planes[idx][0] = a;
    planes[idx][1] = b;
    planes[idx][2] = c;
    planes[idx][3] = d;
  }
  private void normalizePlane(int idx) {
    float a = planes[idx][0], b = planes[idx][1], c = planes[idx][2],
          d = planes[idx][3];
    float len = (float)Math.sqrt(a * a + b * b + c * c);
    if (len > 1e-6f) {
      planes[idx][0] = a / len;
      planes[idx][1] = b / len;
      planes[idx][2] = c / len;
      planes[idx][3] = d / len;
    }
  }
  public boolean isRegionVisible(int regionX, int regionZ, int minY, int maxY) {
    if (!valid)
      return true;
    float minX = regionX * 16.0f;
    float minZ = regionZ * 16.0f;
    float maxX = minX + 16.0f;
    float maxZ = minZ + 16.0f;
    float minYf = (float)minY;
    float maxYf = (float)maxY;
    return aabbIntersectsFrustum(minX, minYf, minZ, maxX, maxYf, maxZ);
  }
  public boolean aabbIntersectsFrustum(float minX, float minY, float minZ,
                                       float maxX, float maxY, float maxZ) {
    for (int i = 0; i < 6; i++) {
      float a = planes[i][0], b = planes[i][1], c = planes[i][2],
            d = planes[i][3];
      float px = a >= 0 ? maxX : minX;
      float py = b >= 0 ? maxY : minY;
      float pz = c >= 0 ? maxZ : minZ;
      if (a * px + b * py + c * pz + d < 0) {
        return false;
      }
    }
    return true;
  }
}
