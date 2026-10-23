select sr.item_code,
       coalesce(max(sn.item_name), sr.item_code) as item_name,
       sum(sr.qty) as requested_qty,
       sum(sr.qty) filter (where sr.status = 'delivered') as delivered_qty,
       sum(sr.qty) filter (where sr.status = 'rejected') as rejected_qty,
       round(100.0 * sum(sr.qty) filter (where sr.status = 'delivered')
             / nullif(sum(sr.qty), 0), 1) as fill_pct
from supply_requests sr
join regiments r on r.id = sr.regiment_id
left join supply_norms sn on sn.item_code = sr.item_code
where (? = '%' or r.code = ?)
group by sr.item_code
order by fill_pct, sr.item_code