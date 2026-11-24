ALTER TABLE hotel ADD COLUMN deleted_at TIMESTAMP;
ALTER TABLE hotel ADD COLUMN deleted_by VARCHAR(36);
ALTER TABLE app_user ADD COLUMN deleted_at TIMESTAMP;
ALTER TABLE app_user ADD COLUMN deleted_by VARCHAR(36);
CREATE INDEX idx_hotel_deleted ON hotel(deleted_at);
CREATE INDEX idx_user_role_deleted ON app_user(role,deleted_at,enabled);
