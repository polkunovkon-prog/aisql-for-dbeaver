select case equipment_type
           when 'weapon' then 'Оружие'
           when 'vehicle' then 'Техника'
           when 'heavy_weapon' then 'Тяжёлое вооружение'
           else equipment_type
       end as equipment_type_ru,
       case status
           when 'operational' then 'Исправно'
           when 'damaged' then 'Повреждено'
           when 'destroyed' then 'Уничтожено'
           when 'maintenance' then 'На обслуживании'
           when 'written_off' then 'Списано'
           else status
       end as status_ru,
       count(*) as cnt,
       round(100.0 * count(*) / sum(count(*)) over (partition by equipment_type), 1) as share_in_type_pct
from equipment
group by equipment_type, status
order by equipment_type, status