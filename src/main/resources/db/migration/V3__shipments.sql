-- Shipment management and tracking.
-- One shipment per fulfilled picking job (picking_job_id is unique), so a sales order
-- with backorders is shipped in several parts. Each status change appends a tracking_event
-- that is owned by (and deleted with) its shipment.

create sequence shipment_SEQ start with 1 increment by 50;
create sequence tracking_event_SEQ start with 1 increment by 50;

create table shipment (
    id bigint not null,
    tracking_number varchar(32) not null,
    carrier varchar(64) not null,
    shipping_method varchar(16) not null check (shipping_method in ('STANDARD','EXPRESS','OVERNIGHT')),
    shipping_cost float(53),
    expected_delivery_date date,
    status varchar(20) not null check (status in ('CREATED','PACKED','DISPATCHED','IN_TRANSIT','OUT_FOR_DELIVERY','DELIVERED','EXCEPTION','RETURNED')),
    sales_order_id bigint not null,
    picking_job_id bigint not null,
    ship_to_address varchar(512),
    created_at timestamp(6) not null,
    dispatched_at timestamp(6),
    delivered_at timestamp(6),
    primary key (id),
    constraint UQ_shipment_tracking_number unique (tracking_number),
    constraint UQ_shipment_picking_job unique (picking_job_id)
);

create table tracking_event (
    id bigint not null,
    shipment_id bigint not null,
    status varchar(20) not null check (status in ('CREATED','PACKED','DISPATCHED','IN_TRANSIT','OUT_FOR_DELIVERY','DELIVERED','EXCEPTION','RETURNED')),
    event_timestamp timestamp(6) not null,
    location varchar(255),
    description varchar(500),
    primary key (id)
);

alter table if exists shipment
    add constraint FKshipment_sales_order
    foreign key (sales_order_id)
    references sales_order;

alter table if exists shipment
    add constraint FKshipment_picking_job
    foreign key (picking_job_id)
    references picking_job;

alter table if exists tracking_event
    add constraint FKtracking_event_shipment
    foreign key (shipment_id)
    references shipment;

create index idx_shipment_sales_order on shipment (sales_order_id);
create index idx_tracking_event_shipment on tracking_event (shipment_id);
