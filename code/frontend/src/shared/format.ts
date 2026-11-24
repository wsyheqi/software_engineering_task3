export const money = (value: number | string | undefined) => Number(value || 0).toLocaleString('zh-CN', {minimumFractionDigits: 2, maximumFractionDigits: 2})
export const shortDate = (date?: string) => date ? date.slice(0, 10).replaceAll('-', '/') : '—'
export const dateTime = (date?: string) => date ? date.replace('T', ' ').slice(0, 19) : '—'
export const statusNames: Record<string, string> = {PENDING: '预订中', CONFIRMED: '已确认', CHECKED_IN: '已入住', CHECKED_OUT: '已退房', CANCELLED: '已取消', FAILED: '预订失败', NO_SHOW: '未入住', RELOCATING: '待安置', CLOSED: '已关闭（停业）', OPEN: '待处理', DISPUTED: '有争议', SUCCEEDED: '已完成', NEEDS_RECONFIRMATION: '待重新确认', REJECTED: '未生效', RESOLVED: '已处理', SENT: '已发送', RETRY: '等待重试', APPLIED: '已扣减', RELEASED: '已解除'}
export const feeNames: Record<string, string> = {LODGING: '住宿费用', CANCELLATION: '取消费用', NO_SHOW: '未入住费用', EXTRA: '提前退房额外费用'}
export const roleNames: Record<string, string> = {ADMIN: '平台管理员', RECEPTION: '学校接待人员', HOTEL: '酒店工作人员', GUEST: '来宾'}
export const addDays = (value: string, days: number) => { const date = new Date(value + 'T12:00:00'); date.setDate(date.getDate() + days); return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}` }
export const nights = (start: string, end: string) => Math.round((new Date(end + 'T12:00:00').getTime() - new Date(start + 'T12:00:00').getTime()) / 86400000)
