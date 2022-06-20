package upe_turma_horario_parser

import com.google.gson.Gson
import technology.tabula.*
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import technology.tabula.extractors.SpreadsheetExtractionAlgorithm
import java.io.File
import java.nio.file.Paths

data class Posicao(val i: Int, val j: Int)
data class Professor(val nome: String, val cargaHoraria: Int){
  constructor(n: String,  ch: String) : this(n,ch.toIntOrNull() ?:  -1)
}
data class Aula(val dia: String, val sala: String,val horarios: List<String>){
  constructor(d: String, s: String, h: String): this(d.uppercase(),s,h.chunked(11))
}

data class DisciplinaPeriodo(
  val codigo: String,
  val nome: String,
  val turma: String,
  val vagas: Int,
  val coordenacao: String,
  val periodo: String,
  val professores: List<Professor>,
  val aulas: List<Aula>
)

data class RelatorioTurmaHorario(
  val coordencao: String,
  val periodo: String,
  val turmas: Sequence<Table>
)

fun getArg(args: Array<String>, arg: String, default: String = "") =
  if(args.contains(arg)) args[1 + args.indexOf(arg)] else default

fun diaParaNum(dia: String) = when(dia.lowercase()){
  "seg" -> 0
  "ter" -> 1
  "qua" -> 2
  "qui" -> 3
  "sex" -> 4
  "sab" -> 5
  "dom" -> 6
  else -> -1
}

fun tabelaValida(tabela:Table) = tabela.colCount >= 3

fun pegarPivo(tabela: Table) = run {
  (0..tabela.colCount).forEach {i ->
    (0..tabela.rowCount).forEach {j ->
    if ("TURMA" == tabela.getCell(i,j).getText().trim())
      return@run Posicao(i,j)
  }}
  return@run Posicao(0,0)
}

fun makeValorEm(tabela:Table) = tabela
  .let(::pegarPivo)
  .let {{ i: Int, j: Int ->  tabela.getCell(it.i + i,it.j + j).getText().trim()}}

fun getProfessores(tabela: Table, valorEm: (Int, Int) -> String) = (3..tabela.rowCount)
  .takeWhile {i -> valorEm(i,0) != "DIA DA SEMANA"}
  .map {Professor(valorEm(it,0), valorEm(it,1))}

fun getAulas(tabela: Table,valorEm: (Int, Int) -> String,posInicial:Int) = (posInicial..tabela.rowCount)
  .takeWhile {diaParaNum(valorEm(it,0)) >= 0}
  .map {Aula(valorEm(it,0), valorEm(it,1),valorEm(it,2))}

fun tabelaParaTurma(tabela: Table, coordenacao: String, periodo: String) = tabela
  .let (::makeValorEm)
  .let { valorEm ->
    val professores = getProfessores(tabela,valorEm)
    val aulas = getAulas(tabela,valorEm,(4 + professores.size))
    return@let DisciplinaPeriodo(
      codigo = valorEm(1,2).split("-",limit=2)[0].trim(),
      nome = valorEm(1,2).split("-",limit=2)[1].trim(),
      turma = valorEm(1,0),
      vagas = valorEm(1,1).toInt(),
      coordenacao = coordenacao,
      periodo = periodo,
      professores = professores,
      aulas = aulas
    )
  }

fun carregarDocs(caminhoArq: String): List<PDDocument> = caminhoArq
  .let(::File)
  .runCatching {when {
      isDirectory -> listFiles()!!.filter {"pdf" in it.extension}.map(PDDocument::load)
      else        -> listOf(PDDocument.load(this))
  }}.getOrElse {
    println("Falha ao Carregar arquivo em ${File(caminhoArq).absolutePath}")
    throw it
  }

fun extrairPaginas(doc: PDDocument) = doc
  .let(::ObjectExtractor)
  .let(ObjectExtractor::extract)
  .asSequence()

fun extrairTabelasDePagina(pagina: Page): MutableList<Table> = pagina
  .let(SpreadsheetExtractionAlgorithm()::extract)

fun extrairTabelas(doc: PDDocument) = doc
  .let(::extrairPaginas)
  .map(::extrairTabelasDePagina)
  .flatten()
  .let {turmas ->
    val (coord,periodo) = PDFTextStripper()
      .apply { startPage=0; endPage = 1;  }
      .run {getText(doc)}
      .split("\n")
      .find { "COORD" in it}!!
      .replace("- POLI", "")
      .run { arrayOf(substringBeforeLast("-"), substringAfterLast("-")) }

    RelatorioTurmaHorario(coord.trim(),periodo.trim(), turmas)
  }

fun transformarTabelas(relTurmas: RelatorioTurmaHorario) = relTurmas.turmas
  .filter(::tabelaValida)
  .map {tabelaParaTurma(it, relTurmas.coordencao, relTurmas.periodo)}

inline fun <reified T> saveAsJson(data: T, path: String) = data
  .let (Gson()::toJson)
  .let (File(path)::writeText)
  .let {File(path)}

fun main(args: Array<String>) = args.runCatching {
  getOrElse(0) {Paths.get(System.getProperty("user.dir"), "resources").toString()}
  .let(::carregarDocs)
  .ifEmpty {throw Exception("[ERR] Não foram encontrados pdfs válidos")}
  .map(::extrairTabelas).also {println("preparando dados para o periodo ${it.first().periodo}")}
  .flatMap(::transformarTabelas)
  .ifEmpty {throw Exception("[ERR] Não foram encontrados disciplinas válidas no(s) arquivo(s)")}
  .let {saveAsJson(it, path = getArg(args, "-o", "./turmas_${it.first().periodo}.json"))}
}
  .onSuccess {println("Dados salvos com sucesso em: ${it.absolutePath}")}
  .onFailure {
    println(it.message)
    if (args.contains("-D")) throw it
  }
  .let{}

    

