/* NSC -- new Scala compiler
 * Copyright 2007-2013 LAMP/EPFL
 * @author  Paul Phillips
 */

package scala.tools.nsc
package doc

import scala.tools.nsc.ast.parser.{ SyntaxAnalyzer, BracePatch }
import scala.reflect.internal.Chars._
import symtab._
import reporters.Reporter
import typechecker.Analyzer
import scala.reflect.internal.util.{ BatchSourceFile, RangePosition }

trait ScaladocAnalyzer extends Analyzer {
  val global : Global // generally, a ScaladocGlobal
  import global._

  override def newTyper(context: Context): ScaladocTyper = new Typer(context) with ScaladocTyper

  trait ScaladocTyper extends Typer {
    private def unit = context.unit

    override def canAdaptConstantTypeToLiteral = false

    override protected def macroImplementationNotFoundMessage(name: Name): String = (
        super.macroImplementationNotFoundMessage(name)
      + "\nWhen generating scaladocs for multiple projects at once, consider using -Ymacro-no-expand to disable macro expansions altogether."
    )

    override def typedDocDef(docDef: DocDef, mode: Mode, pt: Type): Tree = {
      val sym = docDef.symbol

      if ((sym ne null) && (sym ne NoSymbol)) {
        val comment = docDef.comment
        docComments(sym) = comment
        comment.defineVariables(sym)
        val typer1 = newTyper(context.makeNewScope(docDef, context.owner))
        for (useCase <- comment.useCases) {
          typer1.silent(_ => typer1 defineUseCases useCase) match {
            case SilentTypeError(err) =>
              unit.warning(useCase.pos, err.errMsg)
            case _ =>
          }
          for (useCaseSym <- useCase.defined) {
            if (sym.name != useCaseSym.name)
              unit.warning(useCase.pos, "@usecase " + useCaseSym.name.decode + " does not match commented symbol: " + sym.name.decode)
          }
        }
      }

      super.typedDocDef(docDef, mode, pt)
    }

    def defineUseCases(useCase: UseCase): List[Symbol] = {
      def stringParser(str: String): syntaxAnalyzer.Parser = {
        val file = new BatchSourceFile(context.unit.source.file, str) {
          override def positionInUltimateSource(pos: Position) = {
            pos.withSource(context.unit.source, useCase.pos.start)
          }
        }
        newUnitParser(new CompilationUnit(file))
      }

      val trees = stringParser(useCase.body+";").nonLocalDefOrDcl
      val enclClass = context.enclClass.owner

      def defineAlias(name: Name) = (
        if (context.scope.lookup(name) == NoSymbol) {
          lookupVariable(name.toString.substring(1), enclClass) foreach { repl =>
            silent(_.typedTypeConstructor(stringParser(repl).typ())) map { tpt =>
              val alias = enclClass.newAliasType(name.toTypeName, useCase.pos)
              val tparams = cloneSymbolsAtOwner(tpt.tpe.typeSymbol.typeParams, alias)
              val newInfo = genPolyType(tparams, appliedType(tpt.tpe, tparams map (_.tpe)))
              alias setInfo newInfo
              context.scope.enter(alias)
            }
          }
        }
      )

      for (tree <- trees; t <- tree)
        t match {
          case Ident(name) if name startsWith '$' => defineAlias(name)
          case _ =>
        }

      useCase.aliases = context.scope.toList
      namer.enterSyms(trees)
      typedStats(trees, NoSymbol)
      useCase.defined = context.scope.toList filterNot (useCase.aliases contains _)

      if (settings.debug.value)
        useCase.defined foreach (sym => println("defined use cases: %s:%s".format(sym, sym.tpe)))

      useCase.defined
    }
  }
}

abstract class ScaladocSyntaxAnalyzer[G <: Global](val global: G) extends SyntaxAnalyzer {
  import global._

  class ScaladocJavaUnitParser(unit: CompilationUnit) extends {
    override val in = new ScaladocJavaUnitScanner(unit)
  } with JavaUnitParser(unit) { }

  class ScaladocJavaUnitScanner(unit: CompilationUnit) extends JavaUnitScanner(unit) {
    /** buffer for the documentation comment
     */
    var docBuffer: StringBuilder = null

    /** add the given character to the documentation buffer
     */
    protected def putDocChar(c: Char) {
      if (docBuffer ne null) docBuffer.append(c)
    }

    override protected def skipComment(): Boolean = {
      if (in.ch == '/') {
        do {
          in.next
        } while ((in.ch != CR) && (in.ch != LF) && (in.ch != SU))
        true
      } else if (in.ch == '*') {
        docBuffer = null
        in.next
        val scalaDoc = ("/**", "*/")
        if (in.ch == '*')
          docBuffer = new StringBuilder(scalaDoc._1)
        do {
          do {
            if (in.ch != '*' && in.ch != SU) {
              in.next; putDocChar(in.ch)
            }
          } while (in.ch != '*' && in.ch != SU)
          while (in.ch == '*') {
            in.next; putDocChar(in.ch)
          }
        } while (in.ch != '/' && in.ch != SU)
        if (in.ch == '/') in.next
        else incompleteInputError("unclosed comment")
        true
      } else {
        false
      }
    }
  }

  class ScaladocUnitScanner(unit0: CompilationUnit, patches0: List[BracePatch]) extends UnitScanner(unit0, patches0) {

    private var docBuffer: StringBuilder = null        // buffer for comments
    private var docPos: Position         = NoPosition  // last doc comment position
    private var inDocComment             = false

    override def discardDocBuffer() = {
      val doc = flushDoc
      if (doc ne null)
        unit.warning(docPos, "discarding unmoored doc comment")
    }

    override def flushDoc(): DocComment = {
      if (docBuffer eq null) null
      else try DocComment(docBuffer.toString, docPos) finally docBuffer = null
    }

    override protected def putCommentChar() {
      if (inDocComment)
        docBuffer append ch

      nextChar()
    }
    override def skipDocComment(): Unit = {
      inDocComment = true
      docBuffer = new StringBuilder("/**")
      super.skipDocComment()
    }
    override def skipBlockComment(): Unit = {
      inDocComment = false
      docBuffer = new StringBuilder("/*")
      super.skipBlockComment()
    }
    override def skipComment(): Boolean = {
      super.skipComment() && {
        if (docBuffer ne null) {
          if (inDocComment)
            foundDocComment(docBuffer.toString, offset, charOffset - 2)
          else
            try foundComment(docBuffer.toString, offset, charOffset - 2) finally docBuffer = null
        }
        true
      }
    }
    def foundComment(value: String, start: Int, end: Int) {
      val pos = new RangePosition(unit.source, start, start, end)
      unit.comment(pos, value)
    }
    def foundDocComment(value: String, start: Int, end: Int) {
      docPos = new RangePosition(unit.source, start, start, end)
      unit.comment(docPos, value)
    }
  }
  class ScaladocUnitParser(unit: CompilationUnit, patches: List[BracePatch]) extends UnitParser(unit, patches) {
    override def newScanner() = new ScaladocUnitScanner(unit, patches)
    override def withPatches(patches: List[BracePatch]) = new ScaladocUnitParser(unit, patches)

    override def joinComment(trees: => List[Tree]): List[Tree] = {
      val doc = in.flushDoc
      if ((doc ne null) && doc.raw.length > 0) {
        log(s"joinComment(doc=$doc)")
        val joined = trees map {
          t =>
            DocDef(doc, t) setPos {
              if (t.pos.isDefined) {
                val pos = doc.pos.withEnd(t.pos.endOrPoint)
                // always make the position transparent
                pos.makeTransparent
              } else {
                t.pos
              }
            }
        }
        joined.find(_.pos.isOpaqueRange) foreach {
          main =>
            val mains = List(main)
            joined foreach { t => if (t ne main) ensureNonOverlapping(t, mains) }
        }
        joined
      }
      else trees
    }
  }
}
