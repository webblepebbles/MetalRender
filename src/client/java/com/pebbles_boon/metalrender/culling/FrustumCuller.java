package com.pebbles_boon.metalrender.culling;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public class FrustumCuller {
  private final float[][] planes = new float[6][4];

  private final Matrix4f mvp = new Matrix4f();

  public void update(Matrix4f projection, Matrix4f modelView,
      Vector3f cameraPos) {
    projection.mul(modelView, mvp);
    planes[0][0] = mvp.m30() + mvp.m00();
    planes[0][1] = mvp.m31() + mvp.m01();
    planes[0][2] = mvp.m32() + mvp.m02();
    planes[0][3] = mvp.m33() + mvp.m03();
    planes[1][0] = mvp.m30() - mvp.m00();
    planes[1][1] = mvp.m31() - mvp.m01();
    planes[1][2] = mvp.m32() - mvp.m02();
    planes[1][3] = mvp.m33() - mvp.m03();
    planes[2][0] = mvp.m30() + mvp.m10();
    planes[2][1] = mvp.m31() + mvp.m11();
    planes[2][2] = mvp.m32() + mvp.m12();
    planes[2][3] = mvp.m33() + mvp.m13();
    planes[3][0] = mvp.m30() - mvp.m10();
    planes[3][1] = mvp.m31() - mvp.m11();
    planes[3][2] = mvp.m32() - mvp.m12();
    planes[3][3] = mvp.m33() - mvp.m13();
    planes[4][0] = mvp.m30() + mvp.m20();
    planes[4][1] = mvp.m31() + mvp.m21();
    planes[4][2] = mvp.m32() + mvp.m22();
    planes[4][3] = mvp.m33() + mvp.m23();
    planes[5][0] = mvp.m30() - mvp.m20();
    planes[5][1] = mvp.m31() - mvp.m21();
    planes[5][2] = mvp.m32() - mvp.m22();
    planes[5][3] = mvp.m33() - mvp.m23();
    for (int i = 0; i < 6; i++) {
      float len = (float) Math.sqrt(planes[i][0] * planes[i][0] +
          planes[i][1] * planes[i][1] +
          planes[i][2] * planes[i][2]);
      if (len > 0) {
        planes[i][0] /= len;
        planes[i][1] /= len;
        planes[i][2] /= len;
        planes[i][3] /= len;
      }
    }
  }

  public boolean testBoundingBox(float minX, float minY, float minZ, float maxX,
      float maxY, float maxZ) {
    for (int i = 0; i < 6; i++) {
      float a = planes[i][0], b = planes[i][1], c = planes[i][2],
          d = planes[i][3];
      float px = a > 0 ? maxX : minX;
      float py = b > 0 ? maxY : minY;
      float pz = c > 0 ? maxZ : minZ;
      if (a * px + b * py + c * pz + d < 0) {
        return false;
      }
    }
    return true;
  }

  public boolean isRegionVisible(int regionX, int regionZ, int minY, int maxY) {
    float minX = regionX * 16.0f;
    float minZ = regionZ * 16.0f;
    float maxX = minX + 16.0f;
    float maxZ = minZ + 16.0f;
    return testBoundingBox(minX, (float) minY, minZ, maxX, (float) maxY, maxZ);
  }

  public void copyFrom(FrustumCuller other) {
    for (int i = 0; i < 6; i++) {
      System.arraycopy(other.planes[i], 0, this.planes[i], 0, 4);
    }
    this.mvp.set(other.mvp);
  }
}
