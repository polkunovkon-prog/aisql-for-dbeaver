with exam as (
    select avg(score)::numeric(6,1) as avg_score,
           round(100.0 * count(*) filter (where grade = 'Отлично')
                 / nullif(count(*), 0), 1) as excellent_pct
    from exam_results
    where is_active
)
select (select count(*) from students) as students_total,
       (select count(*) from students where is_active) as students_active,
       (select count(*) from employees where is_active) as employees_total,
       (select count(*) from subjects) as subjects_total,
       (select count(*) from exams where is_active) as exams_total,
       exam.avg_score as avg_exam_score,
       exam.excellent_pct,
       (select count(*) from library_records) as library_loans,
       (select count(*) from club_members where is_active) as club_members
from exam