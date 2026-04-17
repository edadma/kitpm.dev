package io.github.edadma.kitd

import io.github.edadma.kit.*

import io.github.edadma.toml.{TomlParser as Toml, TomlDocument, TomlValue}

import scala.collection.immutable.VectorMap

/**
 * System configuration extraction.
 * Parses system.toml and extracts sections that generators declare as inputs.
 */
object SystemConfig:

  /**
   * Parse a generator input reference like "/etc/kit/system.toml#nginx"
   * into the section name ("nginx").
   *
   * @return Some(sectionName) if the input references a system.toml section, None otherwise
   */
  def parseInputRef(input: String): Option[String] =
    val hashIdx = input.indexOf('#')
    if hashIdx < 0 then None
    else if !input.substring(0, hashIdx).endsWith("system.toml") then None
    else Some(input.substring(hashIdx + 1))

  /**
   * Extract a section from a parsed system.toml document.
   *
   * @param doc     the parsed system.toml
   * @param section the section name (e.g., "nginx")
   * @return the section as a string→string map, or None if missing
   */
  def extractSection(doc: TomlDocument, section: String): Option[Map[String, String]] =
    doc.getTable(section).map(tableToStringMap)

  /**
   * Extract all sections referenced by a generator's inputs.
   *
   * @param doc    the parsed system.toml
   * @param inputs the generator's declared input list
   * @return map from section name to its key-value pairs
   */
  def extractInputs(doc: TomlDocument, inputs: List[String]): Map[String, Map[String, String]] =
    inputs
      .flatMap(parseInputRef)
      .distinct
      .flatMap { section =>
        extractSection(doc, section).map(section -> _)
      }
      .toMap

  /**
   * Parse system.toml from a string.
   */
  def parse(input: String): Either[String, TomlDocument] =
    Toml.parse(input).left.map(e => s"TOML parse error: $e")

  /**
   * Compute a content hash of a section's values (for change detection in reconfigure).
   * Uses a stable serialization: sorted keys, newline-separated "key=value" pairs.
   */
  def sectionFingerprint(values: Map[String, String]): String =
    values.toList.sorted.map((k, v) => s"$k=$v").mkString("\n")

  private def tableToStringMap(table: VectorMap[String, TomlValue]): Map[String, String] =
    table.toList.flatMap { (k, v) =>
      v match
        case TomlValue.Str(s)      => Some(k -> s)
        case TomlValue.Num(n)      => Some(k -> n.toString)
        case TomlValue.FloatVal(d) => Some(k -> d.toString)
        case TomlValue.Bool(b)     => Some(k -> b.toString)
        case _                     => None // skip nested tables and arrays
    }.toMap
