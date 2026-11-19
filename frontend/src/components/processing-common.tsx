import type { TaskStatus } from '@/lib/types'

export const taskStatusNames: Record<TaskStatus, string> = { PENDING: '待处理', RUNNING: '处理中', SUCCEEDED: '已完成', REJECTED: '已拒绝', CANCELLED: '已取消' }
