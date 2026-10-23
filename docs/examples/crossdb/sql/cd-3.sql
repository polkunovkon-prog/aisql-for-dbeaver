with person_squad as (
    select p.id,
           sq.platoon_id,
           pl.company_id,
           co.battalion_id,
           bt.regiment_id,
           rg.brigade_id,
           br.division_id,
           dv.corps_id,
           cp.army_id
    from personnel p
    join squads sq on sq.id = p.squad_id
    join platoons pl on pl.id = sq.platoon_id
    join companies co on co.id = pl.company_id
    join battalions bt on bt.id = co.battalion_id
    join regiments rg on rg.id = bt.regiment_id
    join brigades br on br.id = rg.brigade_id
    join divisions dv on dv.id = br.division_id
    join corps cp on cp.id = dv.corps_id
),
counts as (
    select 'army' as unit_type, army_id as unit_id, count(*) as personnel_count
    from person_squad group by army_id
    union all
    select 'corps', corps_id, count(*) from person_squad group by corps_id
    union all
    select 'division', division_id, count(*) from person_squad group by division_id
    union all
    select 'brigade', brigade_id, count(*) from person_squad group by brigade_id
    union all
    select 'regiment', regiment_id, count(*) from person_squad group by regiment_id
    union all
    select 'battalion', battalion_id, count(*) from person_squad group by battalion_id
),
units as (
    select 'army' as unit_type, 1 as level_order, 'Армия' as unit_type_ru,
           a.id, a.code as unit_code, a.name as unit_name
    from armies a
    union all
    select 'corps', 2, 'Корпус', c.id, c.code, c.name from corps c
    union all
    select 'division', 3, 'Дивизия', d.id, d.code, d.name from divisions d
    union all
    select 'brigade', 4, 'Бригада', b.id, b.code, b.name from brigades b
    union all
    select 'regiment', 5, 'Полк', r.id, r.code, r.name from regiments r
    union all
    select 'battalion', 6, 'Батальон', b.id, b.code, b.name from battalions b
)
select u.unit_type_ru,
       u.unit_code,
       u.unit_name,
       coalesce(c.personnel_count, 0) as personnel_count,
       u.level_order
from units u
left join counts c
       on c.unit_type = u.unit_type
      and c.unit_id = u.id
order by u.level_order, u.unit_code