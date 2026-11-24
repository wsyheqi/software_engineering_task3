export type Role = 'ADMIN' | 'RECEPTION' | 'HOTEL' | 'GUEST'
export interface User { id: string; username: string; name: string; role: Role; hotelId: string; guestId: string }
export interface Hotel { id: string; name: string; city: string; address: string; description: string; evaluation: number | null; audited: boolean; version: number; deletedAt?: string | null }
export interface Guest { id: string; name: string; phone: string; identityRef: string; ownerId: string; arrivedAt?: string; departedAt?: string }
export interface Site { id: string; name: string; address: string; version: number }
export interface RoomType { id: string; hotelId: string; code: string; name: string; capacity: number }
export interface Rule { version: number; cancellationText: string; earlyCheckoutText: string; noShowText: string; cancelDeadline?: string; hotelId?: string }
export interface Plan { city: string; siteId: string; startDate: string; endDate: string; budget: number; maxDistance: number; minPrice: number; maxPrice: number; items: {code: string; quantity: number}[]; guestIds: string[]; special: string }
export interface QuoteLine { inventoryId: string; roomTypeId: string; code: string; roomName: string; date: string; quantity: number; price: number; available: number; saleVersion: number }
export interface Snapshot { hotelId: string; hotelName: string; address: string; description: string; evaluation: number; distance: number; plan: Plan; total: number; score: number; lines: QuoteLine[]; rule: Rule }
export interface Quote { quoteId: string; mode: string; snapshot: Snapshot }
export interface Order { id: string; code: string; hotelId: string; hotelName: string; hotelAddress?: string; ownerName: string; ownerId: string; startDate: string; endDate: string; status: string; total: number; version: number; createdAt: string; lateHoldUntil?: string; actualDeparture?: string }
export interface OrderItem { id: string; name: string; code: string; quantity: number; roomTypeId: string }
export interface OrderNight { id: string; itemId: string; roomName: string; stayDate: string; quantity: number; price: number; released: boolean; planActive: boolean }
export interface ActualNight { id: string; itemId: string; stayDate: string; quantity: number; price: number }
export interface Fee { id: string; orderId: string; category: string; amount: number; status: string; note: string; createdAt: string; orderCode?: string }
export interface Approval { id: string; orderId: string; quoteId: string; adminId?: string; hotelUserId?: string; createdAt: string; orderCode?: string; note: string }
export interface Extension { id: string; orderId: string; quoteId: string; status: string; orderCode?: string; createdAt: string }
export interface Relocation { id: string; orderId: string; remainingStart: string; remainingEnd: string; newOrderId?: string; status: string }
export interface OrderDetail { order: Order; plan: Plan; rule: Rule; guests: Guest[]; items: OrderItem[]; nights: OrderNight[]; actualNights: ActualNight[]; fees: Fee[]; approvals: Approval[]; extensions: Extension[]; relocations: Relocation[] }
export interface Inventory { id: string; roomTypeId: string; roomName: string; code: string; stayDate: string; price: number; quota: number; occupied: number; blocked: number; stopSale: boolean; available: number; version: number }
export interface Catalog { hotels: Hotel[]; reportHotels: Hotel[]; sites: Site[]; guests: Guest[]; roomTypes: RoomType[]; rules: Rule[]; distances: {hotelId: string; siteId: string; distanceKm: number; version: number}[]; users: Record<string, unknown>[]; today: string; now: string }
export interface CommandResult { requestId: string; status: string; businessCode: string; message: string; data?: Record<string, unknown>; httpStatus: number }
export interface Notification { id: string; orderId?: string; title: string; body: string; isRead: boolean; createdAt: string }
export interface WorkItem { id: string; kind: string; title: string; detail: string; status: string; hotelId?: string; orderId?: string }
export interface FailedTask { id: string; title: string; channel: string; status: string; attempts: number; lastError: string; nextAttemptAt: string; orderId?: string }
export interface Todos { approvals: Approval[]; extensions: Extension[]; fees: Fee[]; relocations: (Relocation & {orderCode: string})[]; workItems: WorkItem[]; failedTasks: FailedTask[] }
export interface Report { basis: string; from: string; to: string; visits: number; uniquePeople: number; orderCount: number; plannedNights: number; actualNights: number; futureNights: number; plannedCost: number; actualCost: number; fees: Record<string, number>; pendingFees: number; statusCounts: Record<string, number>; daily: {date: string; planned: number; actual: number; plannedCost: number; actualCost: number}[]; orders: Order[]; feeDetails: Fee[]; notes: string }
