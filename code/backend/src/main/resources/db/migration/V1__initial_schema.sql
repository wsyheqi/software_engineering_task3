CREATE TABLE app_user (
 id VARCHAR(36) PRIMARY KEY, username VARCHAR(80) NOT NULL UNIQUE, password_hash VARCHAR(100) NOT NULL,
 display_name VARCHAR(80) NOT NULL, role VARCHAR(24) NOT NULL, hotel_id VARCHAR(36), guest_id VARCHAR(36),
 email VARCHAR(160), enabled BOOLEAN NOT NULL DEFAULT TRUE, version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE guest (
 id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36) NOT NULL, name VARCHAR(80) NOT NULL, phone VARCHAR(32) NOT NULL,
 identity_ref VARCHAR(80) NOT NULL UNIQUE
);
CREATE TABLE reception_site (id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL, address VARCHAR(255) NOT NULL, version BIGINT NOT NULL DEFAULT 0);
CREATE TABLE hotel (
 id VARCHAR(36) PRIMARY KEY, name VARCHAR(100) NOT NULL, city VARCHAR(60) NOT NULL, address VARCHAR(255) NOT NULL,
 description VARCHAR(500) NOT NULL, audited BOOLEAN NOT NULL DEFAULT FALSE, evaluation DECIMAL(5,2), version BIGINT NOT NULL DEFAULT 0,
 CHECK (evaluation IS NULL OR (evaluation >= 0 AND evaluation <= 100))
);
CREATE TABLE hotel_site_distance (
 hotel_id VARCHAR(36) NOT NULL, site_id VARCHAR(36) NOT NULL, distance_km DECIMAL(10,3) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY (hotel_id, site_id), CHECK (distance_km >= 0)
);
CREATE TABLE fee_rule (
 hotel_id VARCHAR(36) PRIMARY KEY, version BIGINT NOT NULL DEFAULT 1, cancellation_text VARCHAR(500) NOT NULL,
 early_checkout_text VARCHAR(500) NOT NULL, no_show_text VARCHAR(500) NOT NULL
);
CREATE TABLE room_type (
 id VARCHAR(36) PRIMARY KEY, hotel_id VARCHAR(36) NOT NULL, code VARCHAR(32) NOT NULL, name VARCHAR(80) NOT NULL,
 capacity INT NOT NULL, UNIQUE (hotel_id,code), CHECK (capacity > 0)
);
CREATE TABLE daily_inventory (
 id VARCHAR(36) PRIMARY KEY, hotel_id VARCHAR(36) NOT NULL, room_type_id VARCHAR(36) NOT NULL, stay_date DATE NOT NULL,
 price DECIMAL(12,2) NOT NULL, quota INT NOT NULL, occupied INT NOT NULL DEFAULT 0, blocked INT NOT NULL DEFAULT 0,
 stop_sale BOOLEAN NOT NULL DEFAULT FALSE, version BIGINT NOT NULL DEFAULT 0,
 UNIQUE(hotel_id,room_type_id,stay_date), CHECK(price >= 0), CHECK(quota >= 0), CHECK(occupied >= 0), CHECK(blocked >= 0), CHECK(quota - blocked >= occupied)
);
CREATE TABLE inventory_block (
 id VARCHAR(36) PRIMARY KEY, hotel_id VARCHAR(36) NOT NULL, room_type_id VARCHAR(36) NOT NULL, start_date DATE NOT NULL,
 end_date DATE NOT NULL, quantity INT NOT NULL, reason VARCHAR(500) NOT NULL, status VARCHAR(24) NOT NULL, created_at TIMESTAMP NOT NULL
);
CREATE TABLE stay_demand (
 id VARCHAR(36) PRIMARY KEY, owner_id VARCHAR(36) NOT NULL, plan_json TEXT NOT NULL,
 version BIGINT NOT NULL DEFAULT 1, created_at TIMESTAMP NOT NULL
);
CREATE TABLE quote_snapshot (
 id VARCHAR(36) PRIMARY KEY, actor_id VARCHAR(36) NOT NULL, demand_id VARCHAR(36), demand_version BIGINT,
 hotel_id VARCHAR(36) NOT NULL, order_id VARCHAR(36), mode VARCHAR(24) NOT NULL, order_version BIGINT,
 snapshot_json TEXT NOT NULL, snapshot_hash VARCHAR(64) NOT NULL, created_at TIMESTAMP NOT NULL
);
CREATE TABLE booking_order (
 id VARCHAR(36) PRIMARY KEY, code VARCHAR(40) NOT NULL UNIQUE, owner_id VARCHAR(36) NOT NULL, hotel_id VARCHAR(36) NOT NULL,
 demand_id VARCHAR(36), start_date DATE NOT NULL, end_date DATE NOT NULL, status VARCHAR(24) NOT NULL,
 total DECIMAL(14,2) NOT NULL, plan_json TEXT NOT NULL, rule_json TEXT NOT NULL, version BIGINT NOT NULL DEFAULT 1,
 late_hold_until TIMESTAMP, actual_departure DATE, source_order_id VARCHAR(36), created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_order_hotel_state_dates ON booking_order(hotel_id,status,start_date,end_date);
CREATE INDEX idx_order_owner_created ON booking_order(owner_id,created_at);
CREATE TABLE order_guest (
 order_id VARCHAR(36) NOT NULL, guest_id VARCHAR(36) NOT NULL, PRIMARY KEY(order_id,guest_id)
);
CREATE TABLE order_item (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, room_type_id VARCHAR(36) NOT NULL,
 code VARCHAR(32) NOT NULL, name VARCHAR(80) NOT NULL, quantity INT NOT NULL, UNIQUE(order_id,room_type_id)
);
CREATE TABLE order_night (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, item_id VARCHAR(36) NOT NULL, inventory_id VARCHAR(36) NOT NULL,
 stay_date DATE NOT NULL, quantity INT NOT NULL, price DECIMAL(12,2) NOT NULL, released BOOLEAN NOT NULL DEFAULT FALSE,
 plan_active BOOLEAN NOT NULL DEFAULT TRUE, UNIQUE(item_id,stay_date)
);
CREATE INDEX idx_night_date_order ON order_night(stay_date,order_id);
CREATE TABLE order_revision (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, action_type VARCHAR(40) NOT NULL,
 snapshot_json TEXT NOT NULL, created_at TIMESTAMP NOT NULL
);
CREATE TABLE guest_checkin (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, guest_id VARCHAR(36) NOT NULL,
 arrived_at TIMESTAMP NOT NULL, departed_at TIMESTAMP, UNIQUE(order_id,guest_id)
);
CREATE TABLE actual_room_night (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, item_id VARCHAR(36) NOT NULL, stay_date DATE NOT NULL,
 quantity INT NOT NULL, price DECIMAL(12,2) NOT NULL, recorded_by VARCHAR(36) NOT NULL,
 UNIQUE(item_id,stay_date), CHECK(quantity > 0)
);
CREATE INDEX idx_actual_date_order ON actual_room_night(stay_date,order_id);
CREATE TABLE fee_record (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, category VARCHAR(24) NOT NULL, amount DECIMAL(14,2) NOT NULL,
 source_key VARCHAR(180) NOT NULL UNIQUE, status VARCHAR(24) NOT NULL, note VARCHAR(1000) NOT NULL,
 proposed_by VARCHAR(36) NOT NULL, created_at TIMESTAMP NOT NULL, CHECK(amount >= 0)
);
CREATE TABLE special_change_approval (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, quote_id VARCHAR(36) NOT NULL,
 snapshot_hash VARCHAR(64) NOT NULL, admin_id VARCHAR(36), admin_at TIMESTAMP, hotel_user_id VARCHAR(36), hotel_at TIMESTAMP,
 note VARCHAR(1000) NOT NULL, created_at TIMESTAMP NOT NULL
);
CREATE TABLE extension_request (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, quote_id VARCHAR(36) NOT NULL,
 proposed_by VARCHAR(36) NOT NULL, status VARCHAR(24) NOT NULL, created_at TIMESTAMP NOT NULL
);
CREATE TABLE hotel_closure (
 id VARCHAR(36) PRIMARY KEY, hotel_id VARCHAR(36) NOT NULL, start_date DATE NOT NULL, end_date DATE NOT NULL,
 reason VARCHAR(1000) NOT NULL, created_at TIMESTAMP NOT NULL
);
CREATE TABLE relocation_case (
 id VARCHAR(36) PRIMARY KEY, order_id VARCHAR(36) NOT NULL, closure_id VARCHAR(36) NOT NULL,
 remaining_start DATE NOT NULL, remaining_end DATE NOT NULL, new_order_id VARCHAR(36) UNIQUE,
 status VARCHAR(24) NOT NULL, UNIQUE(order_id,closure_id)
);
CREATE TABLE operation_request (
 id VARCHAR(100) PRIMARY KEY, actor_id VARCHAR(36) NOT NULL, action_type VARCHAR(40) NOT NULL,
 payload_hash VARCHAR(64) NOT NULL, payload_json TEXT NOT NULL, status VARCHAR(30) NOT NULL,
 result_json TEXT, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_request_status_time ON operation_request(status,created_at);
CREATE TABLE notification_task (
 id VARCHAR(36) PRIMARY KEY, event_key VARCHAR(180) NOT NULL, recipient_id VARCHAR(36) NOT NULL,
 channel VARCHAR(16) NOT NULL, order_id VARCHAR(36), hotel_id VARCHAR(36), title VARCHAR(160) NOT NULL,
 body VARCHAR(2000) NOT NULL, status VARCHAR(24) NOT NULL, attempts INT NOT NULL DEFAULT 0,
 next_attempt_at TIMESTAMP NOT NULL, last_error VARCHAR(500), created_at TIMESTAMP NOT NULL,
 UNIQUE(event_key,recipient_id,channel)
);
CREATE INDEX idx_task_status_next ON notification_task(status,next_attempt_at);
CREATE TABLE notification (
 id VARCHAR(36) PRIMARY KEY, task_id VARCHAR(36) NOT NULL UNIQUE, recipient_id VARCHAR(36) NOT NULL,
 order_id VARCHAR(36), title VARCHAR(160) NOT NULL, body VARCHAR(2000) NOT NULL, is_read BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMP NOT NULL
);
CREATE TABLE work_item (
 id VARCHAR(36) PRIMARY KEY, source_key VARCHAR(180) NOT NULL UNIQUE, kind VARCHAR(40) NOT NULL, hotel_id VARCHAR(36),
 order_id VARCHAR(36), title VARCHAR(200) NOT NULL, detail VARCHAR(2000) NOT NULL, status VARCHAR(24) NOT NULL,
 created_at TIMESTAMP NOT NULL
);
CREATE TABLE audit_log (
 id VARCHAR(36) PRIMARY KEY, actor_id VARCHAR(36), actor_name VARCHAR(80) NOT NULL, actor_role VARCHAR(24) NOT NULL,
 hotel_id VARCHAR(36), order_id VARCHAR(36), request_id VARCHAR(100), action_type VARCHAR(80) NOT NULL,
 object_id VARCHAR(100), outcome VARCHAR(30) NOT NULL, detail VARCHAR(1000) NOT NULL, created_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_audit_hotel_time ON audit_log(hotel_id,created_at);
