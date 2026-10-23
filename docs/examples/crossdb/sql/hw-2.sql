with h as (
    select id, code, name from houses
),
st as (
    select house_id,
           count(*) as students_total,
           count(*) filter (where is_active) as students_active
    from students
    group by house_id
),
ex as (
    select s.house_id,
           round(avg(er.score)::numeric, 1) as avg_exam_score,
           round(100.0 * count(*) filter (where er.grade = 'Отлично')
                 / nullif(count(*), 0), 1) as excellent_pct
    from exam_results er
    join students s on s.id = er.student_id
    where er.is_active
    group by s.house_id
),
en as (
    select s.house_id,
           round(100.0 * count(*) filter (where e.status = 'completed')
                 / nullif(count(*), 0), 1) as completed_pct
    from enrollments e
    join students s on s.id = e.student_id
    group by s.house_id
),
cl as (
    select s.house_id, count(*) as club_members
    from club_members cm
    join students s on s.id = cm.student_id
    where cm.is_active
    group by s.house_id
),
lib as (
    select s.house_id, count(*) as library_loans
    from library_records lr
    join students s on s.id = lr.student_id
    group by s.house_id
)
select h.code as house_code,
       h.name as house_name,
       coalesce(st.students_total, 0) as students_total,
       coalesce(st.students_active, 0) as students_active,
       ex.avg_exam_score,
       ex.excellent_pct,
       en.completed_pct,
       coalesce(cl.club_members, 0) as club_members,
       coalesce(lib.library_loans, 0) as library_loans,
       rank() over (order by ex.avg_exam_score desc nulls last) as score_rank,
       avg(ex.avg_exam_score) over () as overall_avg_score
from h
left join st on st.house_id = h.id
left join ex on ex.house_id = h.id
left join en on en.house_id = h.id
left join cl on cl.house_id = h.id
left join lib on lib.house_id = h.id
where (? = '%' or h.code = ?)
order by score_rank, h.name