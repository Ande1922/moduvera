#!/usr/bin/env python3
"""Small fail-closed MyBatis XML model for the frozen T01 SQL subset."""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from xml.etree import ElementTree


STORE_COLUMNS = frozenset(
    {
        "tenant_id",
        "store_id",
        "code",
        "name",
        "time_zone_id",
        "status",
        "version",
        "created_at",
        "created_by_type",
        "created_by_id",
        "updated_at",
        "updated_by_type",
        "updated_by_id",
    }
)
CONDITIONAL_TAGS = frozenset({"if", "choose", "when", "otherwise", "foreach", "bind"})
STATEMENT_TAGS = frozenset({"select", "insert", "update", "delete"})


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


@dataclass(frozen=True)
class ResultMap:
    qualified_id: str
    root_type: str
    columns: frozenset[str]


@dataclass(frozen=True)
class Statement:
    qualified_id: str
    operation: str
    sql: str
    result_type: str | None
    result_columns: frozenset[str]
    parameter_type: str | None
    dynamic_tags: frozenset[str]


@dataclass(frozen=True)
class MapperModel:
    path: Path
    namespace: str
    statements: tuple[Statement, ...]
    result_maps: dict[str, ResultMap]


def _collect_columns(element: ElementTree.Element) -> frozenset[str]:
    columns = set()
    for child in element.iter():
        if local_name(child.tag) in {"id", "result", "association", "collection"}:
            column = child.attrib.get("column")
            if column:
                columns.update(item.strip().lower() for item in column.split(",") if item.strip())
    return frozenset(columns)


def _render(
    element: ElementTree.Element,
    fragments: dict[str, ElementTree.Element],
    namespace: str,
    stack: tuple[str, ...] = (),
) -> tuple[str, frozenset[str]]:
    parts = [element.text or ""]
    dynamic = set()
    for child in element:
        tag = local_name(child.tag)
        if tag == "include":
            reference = child.attrib.get("refid", "")
            local_reference = reference.rsplit(".", 1)[-1]
            if reference.startswith(namespace + "."):
                local_reference = reference[len(namespace) + 1 :]
            if local_reference in stack:
                raise ValueError(f"recursive <include>: {' -> '.join((*stack, local_reference))}")
            fragment = fragments.get(local_reference)
            if fragment is None:
                raise ValueError(f"unresolved <include refid={reference!r}>")
            text, nested_dynamic = _render(fragment, fragments, namespace, (*stack, local_reference))
            parts.append(text)
            dynamic.update(nested_dynamic)
        else:
            if tag in CONDITIONAL_TAGS:
                dynamic.add(tag)
            text, nested_dynamic = _render(child, fragments, namespace, stack)
            parts.append(text)
            dynamic.update(nested_dynamic)
        parts.append(child.tail or "")
    return " ".join(parts), frozenset(dynamic)


def _result_map(
    element: ElementTree.Element,
    namespace: str,
    raw_maps: dict[str, ElementTree.Element],
    cache: dict[str, ResultMap],
    stack: tuple[str, ...] = (),
) -> ResultMap:
    map_id = element.attrib["id"]
    if map_id in cache:
        return cache[map_id]
    if map_id in stack:
        raise ValueError(f"recursive resultMap extends: {' -> '.join((*stack, map_id))}")
    parent_columns: frozenset[str] = frozenset()
    parent_type: str | None = None
    extends = element.attrib.get("extends")
    if extends:
        parent_id = extends.rsplit(".", 1)[-1]
        parent = raw_maps.get(parent_id)
        if parent is None:
            raise ValueError(f"unresolved resultMap extends={extends!r}")
        resolved_parent = _result_map(parent, namespace, raw_maps, cache, (*stack, map_id))
        parent_columns = resolved_parent.columns
        parent_type = resolved_parent.root_type
    root_type = element.attrib.get("type") or parent_type
    if not root_type:
        raise ValueError(f"resultMap {map_id!r} has no type")
    result = ResultMap(
        qualified_id=f"{namespace}.{map_id}",
        root_type=root_type,
        columns=parent_columns | _collect_columns(element),
    )
    cache[map_id] = result
    return result


def parse_mapper(path: Path) -> MapperModel:
    root = ElementTree.parse(path).getroot()
    if local_name(root.tag) != "mapper":
        raise ValueError("root element is not <mapper>")
    namespace = root.attrib.get("namespace", "").strip()
    if not namespace:
        raise ValueError("mapper namespace is missing")
    fragments = {
        child.attrib["id"]: child
        for child in root
        if local_name(child.tag) == "sql" and child.attrib.get("id")
    }
    raw_maps = {
        child.attrib["id"]: child
        for child in root
        if local_name(child.tag) == "resultMap" and child.attrib.get("id")
    }
    maps: dict[str, ResultMap] = {}
    for element in raw_maps.values():
        _result_map(element, namespace, raw_maps, maps)

    statements = []
    for element in root:
        operation = local_name(element.tag)
        if operation not in STATEMENT_TAGS:
            continue
        statement_id = element.attrib.get("id")
        if not statement_id:
            raise ValueError(f"{operation} statement has no id")
        sql, dynamic = _render(element, fragments, namespace)
        result_type = element.attrib.get("resultType")
        result_columns: frozenset[str] = frozenset()
        result_map_reference = element.attrib.get("resultMap")
        if result_map_reference:
            local_reference = result_map_reference.rsplit(".", 1)[-1]
            resolved = maps.get(local_reference)
            if resolved is None:
                raise ValueError(f"unresolved resultMap={result_map_reference!r}")
            result_type = resolved.root_type
            result_columns = resolved.columns
        statements.append(
            Statement(
                qualified_id=f"{namespace}.{statement_id}",
                operation=operation,
                sql=canonical_sql(sql),
                result_type=result_type,
                result_columns=result_columns,
                parameter_type=element.attrib.get("parameterType"),
                dynamic_tags=dynamic,
            )
        )
    return MapperModel(path, namespace, tuple(statements), maps)


def canonical_sql(value: str) -> str:
    value = re.sub(r"/\*.*?\*/", " ", value, flags=re.DOTALL)
    value = re.sub(r"--[^\n\r]*", " ", value)
    value = re.sub(r"'(?:''|[^'])*'", "'?'", value)
    value = re.sub(r'"(?:""|[^"])*"', '"?"', value)
    return re.sub(r"\s+", " ", value).strip().lower()


def bound_equality(where_sql: str, column: str) -> str | None:
    match = re.search(
        rf"(?:\b[a-z_][\w]*\.)?\b{re.escape(column)}\b\s*=\s*#\{{\s*([^,}}]+)",
        where_sql,
        flags=re.IGNORECASE,
    )
    return match.group(1).strip() if match else None


def split_top_level(value: str) -> list[str]:
    result = []
    start = 0
    parentheses = 0
    braces = 0
    for index, character in enumerate(value):
        if character == "(":
            parentheses += 1
        elif character == ")":
            parentheses -= 1
        elif character == "{" and index > 0 and value[index - 1] in {"#", "$"}:
            braces += 1
        elif character == "}" and braces:
            braces -= 1
        elif character == "," and parentheses == 0 and braces == 0:
            result.append(value[start:index].strip())
            start = index + 1
    result.append(value[start:].strip())
    return result


def parse_insert(statement: Statement) -> tuple[list[str], list[str]] | None:
    match = re.search(
        r"\binsert\s+into\s+(?:`?\w+`?\.)?`?store_location`?\s*\((.*?)\)\s*values\s*\((.*?)\)\s*$",
        statement.sql,
        flags=re.IGNORECASE | re.DOTALL,
    )
    if match is None:
        return None
    columns = [item.strip().strip("`").lower() for item in split_top_level(match.group(1))]
    return columns, split_top_level(match.group(2))


def store_statements(models: list[MapperModel]) -> list[Statement]:
    return [statement for model in models for statement in model.statements if re.search(r"\bstore_location\b", statement.sql)]
