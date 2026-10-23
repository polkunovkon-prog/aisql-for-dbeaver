with as_of as (
    select max(casualty_date) as as_of_date from casualties
),
base as (
    select r.category,
           r.grade,
           p.id,
           p.status,
           p.is_officer,
           extract(year from age(a.as_of_date, p.enlist_date)) as service_years
    from personnel p
    join ranks r on r.id = p.rank_id
    cross join as_of a
)
select case category
           when 'enlisted' then 'Солдаты'
           when 'nco' then 'Сержанты'
           when 'officer' then 'Офицеры'
           when 'senior_officer' then 'Старшие офицеры'
           when 'general' then 'Генералитет'
           else category
       end as rank_category_ru,
       count(*) as personnel_total,
       count(*) filter (where status = 'active') as active_total,
       count(*) filter (where status = 'killed') as killed_total,
       count(*) filter (where status = 'wounded') as wounded_total,
       round(avg(service_years)::numeric, 1) as avg_service_years,
       round(100.0 * count(*) / sum(count(*)) over (), 1) as share_pct,
       min(grade) as category_order
from base
group by category
order by category_order