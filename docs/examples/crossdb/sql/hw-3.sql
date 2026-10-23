select h.code as house_code,
       h.name as house_name,
       s.grade_level,
       er.grade as grade_label,
       er.score,
       1 as cnt
from exam_results er
join students s on s.id = er.student_id
join houses h on h.id = s.house_id
where er.is_active
  and (? = '%' or h.code = ?)
order by h.name, s.grade_level, er.score