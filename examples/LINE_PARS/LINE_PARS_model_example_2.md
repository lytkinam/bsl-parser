# LINE_PARS модель: example_2

## Узлы

| ID | SDBL ID | Имя | Тип | Родитель | Подзапросы | UNION части |
|---|---|---|---|---|---|---|
| 0 | 0 | вт_НашиДоговора | temp_query | — | — | — |
| 1 | 1 | ЗЛО | temp_query | — | — | 2, 3, 4 |
| 2 | 1 | ЗЛО_UNION_0 | union_query | — | — | — |
| 3 | 1 | ЗЛО_UNION_1 | union_query | — | — | — |
| 4 | 1 | ЗЛО_UNION_2 | union_query | — | — | — |
| 5 | 2 | Результат_3 | result | — | 6 | — |
| 6 | 2 | Результат_3_SUB_1 | sub_query | 5 | — | — |

