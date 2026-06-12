import type { FrameDataPayload } from '../types/messages';

/**
 * 从 video 元素截取当前帧，返回完整 JPEG 帧数据和缩略图哈希。
 *
 * 用于帧差检测的两步策略：
 * 1. 先生成 64x64 缩略图并计算哈希
 * 2. 与上一帧哈希比对，有变化时才编码完整帧
 * 3. 完整帧也缩放到 maxWidth 以控制数据量
 *
 * 注意：此操作使用离屏 Canvas，不影响 DOM 渲染。
 *
 * @param video    正在播放的 HTMLVideoElement（readyState >= 2）
 * @param maxWidth 完整帧的最大宽度，超出时等比缩放
 * @returns 完整帧数据 + 缩略图哈希，video 未就绪时返回 null
 */
export function captureFrame(
  video: HTMLVideoElement,
  maxWidth: number = 640
): { fullFrame: FrameDataPayload; thumbnailHash: string } | null {
  if (video.readyState < 2) return null;

  const vw = video.videoWidth;
  const vh = video.videoHeight;
  if (vw === 0 || vh === 0) return null;

  // ===== 完整帧：等比缩放到 maxWidth =====
  const scale = Math.min(1, maxWidth / vw);
  const fw = Math.floor(vw * scale);
  const fh = Math.floor(vh * scale);

  const fullCanvas = document.createElement('canvas');
  fullCanvas.width = fw;
  fullCanvas.height = fh;
  const fctx = fullCanvas.getContext('2d')!;
  fctx.drawImage(video, 0, 0, fw, fh);

  // Canvas → JPEG base64（quality=0.7），去掉 data:image 前缀
  const fullData = fullCanvas.toDataURL('image/jpeg', 0.7);

  // ===== 缩略图：64x64 用于哈希计算 =====
  const thumbCanvas = document.createElement('canvas');
  thumbCanvas.width = 64;
  thumbCanvas.height = 64;
  const tctx = thumbCanvas.getContext('2d')!;
  tctx.drawImage(video, 0, 0, 64, 64);

  const thumbData = thumbCanvas.toDataURL('image/jpeg', 0.3);
  const thumbnailHash = simpleHash(thumbData);

  return {
    fullFrame: {
      format: 'jpeg',
      width: fw,
      height: fh,
      data: fullData.split(',')[1], // 去掉 "data:image/jpeg;base64," 前缀
      changed: false,               // 由调用方根据帧差检测结果设置
      imageChecksum: thumbnailHash,
    },
    thumbnailHash,
  };
}

/**
 * 帧差检测 —— 对比当前帧与上一帧的缩略图哈希。
 * 缩略图哈希不同 = 画面有显著变化。
 *
 * @param currentHash  当前帧的 64x64 缩略图哈希
 * @param previousHash 上一帧的缩略图哈希（首次为 null 时视为有变化）
 * @returns true=画面变化了
 */
export function hasFrameChanged(
  currentHash: string,
  previousHash: string | null
): boolean {
  if (previousHash === null) return true;  // 首帧总是视为有变化
  return currentHash !== previousHash;
}

/**
 * 简单哈希函数 —— 用于图片缩略图去重。
 * 基于 Java String.hashCode() 算法实现。
 */
function simpleHash(str: string): string {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    const char = str.charCodeAt(i);
    hash = ((hash << 5) - hash) + char;
    hash |= 0;  // 转为 32 位整数
  }
  return hash.toString(16);
}
