/**
 * 上传前压缩笔记图片。
 *
 * 手机直出的照片通常 3–8 MB，直接传既费学生流量也占服务器上行带宽；
 * 笔记场景只需要看清字迹，长边 1600px、JPEG 0.85 足够（约 200–500 KB）。
 * 已经是小图或压缩后反而更大时，原样返回，不做无意义的二次编码。
 */

const MAX_EDGE = 1600
const QUALITY = 0.85
/** 小于这个体积且尺寸不超限的图直接放行。 */
const SMALL_BYTES = 800 * 1024

export async function compressImage(file: File): Promise<File> {
  if (!file.type.startsWith('image/')) return file
  // GIF 动图一旦重编码会丢帧，保持原样。
  if (file.type === 'image/gif') return file

  let bitmap: ImageBitmap
  try {
    bitmap = await createImageBitmap(file)
  } catch {
    // 浏览器解不了（极少数格式）就交给服务端判定，最终由魔数校验兜底。
    return file
  }

  try {
    const longEdge = Math.max(bitmap.width, bitmap.height)
    const scale = longEdge > MAX_EDGE ? MAX_EDGE / longEdge : 1
    if (scale === 1 && file.size <= SMALL_BYTES) return file

    const width = Math.max(1, Math.round(bitmap.width * scale))
    const height = Math.max(1, Math.round(bitmap.height * scale))
    const canvas = document.createElement('canvas')
    canvas.width = width
    canvas.height = height
    const context = canvas.getContext('2d')
    if (!context) return file
    // PNG 透明区域转 JPEG 会变黑，先铺白底。
    context.fillStyle = '#ffffff'
    context.fillRect(0, 0, width, height)
    context.drawImage(bitmap, 0, 0, width, height)

    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, 'image/jpeg', QUALITY),
    )
    if (!blob || blob.size >= file.size) return file
    const baseName = file.name.replace(/\.[^.]+$/, '')
    return new File([blob], `${baseName}.jpg`, { type: 'image/jpeg' })
  } finally {
    bitmap.close()
  }
}
