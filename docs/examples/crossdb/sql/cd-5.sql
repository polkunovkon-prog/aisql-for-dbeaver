select extract(year from o.order_date)::text as year_label,
       case o.order_type
           when 'combat' then 'Боевые'
           when 'discipline' then 'Дисциплинарные'
           when 'personnel' then 'Кадровые'
           when 'supply' then 'Снабжение'
           when 'transfer' then 'Переводы'
           else o.order_type
       end as order_type_ru,
       count(*) as cnt
from orders o
join regiments r on r.id = o.regiment_id
where (? = '%' or r.code = ?)
group by 1, 2
order by 1, 2