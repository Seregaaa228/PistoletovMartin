# Лицензии дополнительных интеграций PIS-1

Тексты сохранены отдельно от неизменённых закреплённых JAR. Проверены официальные
Modrinth version ID из `integration-libraries.lock.json`: SHA512 файла выпуска
совпадает с lock, ID лицензии из project metadata совпадает с соответствующим
JSON здесь. Modrinth публикует лицензию на уровне проекта; это наблюдение на
2026-10-25, а не утверждение об исторической неизменности metadata.

В каждом `<name>.json` записаны URL, immutable Git commit/ref, Git blob SHA,
SHA256 исходных байтов, дата выпуска и ограничения привязки. `source-version-evidence.txt`
содержит явно обозначенную выдержку из gradle.properties того же коммита.

| Библиотека | Основание выбранного upstream ref | Сохранённый текст |
|---|---|---|
| LDLib 1.0.52.a | release commit `1d4e4390ae58d16038845c651e122185c29f334b`, mod_version=1.0.52.a | `ldlib/LICENSE` |
| SmartBrainLib 1.15 | commit «Port 1.15 changes» `cec6c7835b5bf24d65196c58cfdc4b1158a1a9f1`, version=1.15 | `smartbrainlib/LICENSE` |
| Jade 11.13.3 | tag `forge-11.13.3`, commit `d7a9e1d3b782f2ea4c387c66e973cc46b9768270` | `jade/LICENSE.md` |
| Patchouli 84.1 | tag `release-1.20.1-84.1`, commit `4bde361ba2f15d12a983a668baa389811a639f20` | `patchouli/LICENSE` |
| KubeJS 2001.6.5-build.26 | historical branch 2001 commit `ba142541dcc1d230383f4a55e38dd92ff10d1029`, mod_version=2001.6.5 | `kubejs/LICENSE.txt` |
| Rhino 2001.2.3-build.10 | historical branch 2001 commit `afe5010920b6fb3403803341a5931160e84d8940`, mod_version=2001.2.3 | `rhino/LICENSE` |

Для KubeJS и Rhino точных version tags нет. Выбран последний коммит официальной
ветки 2001 не позднее даты публикации закреплённого файла; базовая версия совпала.
Привязка конкретного номера CI build к этому коммиту независимо не подтверждена.
Это исторические исходники, а не подмена лицензией из новой ветки.

У LDLib есть фактическое расхождение: Modrinth объявляет `LGPL-3.0-only`,
но root LICENSE точного release commit содержит GPL v3. Оба исходных объявления
сохранены без исправления и без вывода о том, какое разрешение имеет приоритет.
Остальные пять сохранённых upstream текстов соответствуют наблюдаемым ID metadata.

Это сохранение происхождения и объявлений, без юридической оценки разрешений.
Зависимости, JAR и lock-файлы этим действием не изменялись.

Файлы хранятся побайтно, включая четыре строки с исходными концевыми пробелами
в `ldlib/LICENSE`. Они намеренно не форматируются: Git blob SHA совпадает с
upstream. Проверка whitespace собственных изменений выполняется с исключением
только этого файла: `git diff --cached --check -- . ':!licenses/integrations/ldlib/LICENSE'`.
