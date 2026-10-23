with p as (
    select count(*) as personnel_total,
           count(*) filter (where status = 'active') as personnel_active,
           count(*) filter (where is_officer) as officers
    from personnel
),
c as (
    select count(*) as casualties_total from casualties
),
e as (
    select count(*) as equipment_total,
           count(*) filter (where status = 'operational') as equipment_operational
    from equipment
),
s as (
    select sum(qty) as requested_qty,
           sum(qty) filter (where status = 'delivered') as delivered_qty
    from supply_requests
)
select p.personnel_total,
       p.personnel_active,
       round(100.0 * p.officers / nullif(p.personnel_total, 0), 1) as officers_pct,
       c.casualties_total,
       e.equipment_total,
       round(100.0 * e.equipment_operational / nullif(e.equipment_total, 0), 1) as equipment_operational_pct,
       s.requested_qty,
       s.delivered_qty,
       round(100.0 * s.delivered_qty / nullif(s.requested_qty, 0), 1) as supply_delivered_pct
from p, c, e, s