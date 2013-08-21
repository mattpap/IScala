package org.refptr.iscala

import scala.tools.nsc.interpreter.{IMain,Parsed,Completion,CompletionOutput,NamedParam}
import scala.collection.mutable.ListBuffer

import scala.reflect.NameTransformer

/** An interface for objects which are aware of tab completion and
 *  will supply their own candidates and resolve their own paths.
 */
trait CompletionAware {
    /** The complete list of unqualified Strings to which this
     *  object will complete.
     */
    def completions: List[String]

    /** The next completor in the chain.
     */
    def follow(id: String): Option[CompletionAware] = None

    /** Given string 'buf', return a list of all the strings
     *  to which it can complete.  This may involve delegating
     *  to other CompletionAware objects.
     */
    def completionsFor(parsed: Parsed): List[String] = {
        val comps = completions filter (_ startsWith parsed.buffer)

        val results =
            if (parsed.isEmpty || (parsed.isUnqualified && !parsed.isLastDelimiter)) comps
            else follow(parsed.bufferHead) map (_ completionsFor parsed.bufferTail) getOrElse Nil

        results.sorted
    }
}

object CompletionAware {
    val Empty = new CompletionAware { def completions = Nil }

    def unapply(that: Any): Option[CompletionAware] = that match {
        case x: CompletionAware => Some((x))
        case _                  => None
    }

    /** Create a CompletionAware object from the given functions.
     *  The first should generate the list of completions whenever queried,
     *  and the second should return Some(CompletionAware) object if
     *  subcompletions are possible.
     */
    def apply(terms: () => List[String], followFunction: String => Option[CompletionAware]): CompletionAware =
        new CompletionAware {
            def completions = terms()
            override def follow(id: String) = followFunction(id)
        }

    /** Convenience factories.
     */
    def apply(terms: () => List[String]): CompletionAware = apply(terms, _ => None)
    def apply(map: scala.collection.Map[String, CompletionAware]): CompletionAware =
        apply(() => map.keys.toList, map.get _)
}

class IScalaCompletion(val intp: IMain) extends Completion with CompletionOutput {
    val global: intp.global.type = intp.global
    import global._
    import definitions.{PredefModule,AnyClass,AnyRefClass,ScalaPackage,JavaLangPackage}
    import rootMirror.{RootClass,getModuleIfDefined}
    import Completion._
    type ExecResult = Any

    // verbosity goes up with consecutive tabs
    private var verbosity: Int = 0
    def resetVerbosity() = verbosity = 0

    def getSymbol(name: String, isModule: Boolean) = (
        if (isModule) getModuleIfDefined(name)
        else getModuleIfDefined(name)
    )
    def getType(name: String, isModule: Boolean) = getSymbol(name, isModule).tpe
    def typeOf(name: String)                     = getType(name, false)
    def moduleOf(name: String)                   = getType(name, true)

    trait CompilerCompletion {
        def tp: Type
        def effectiveTp = tp match {
            case MethodType(Nil, resType)   => resType
            case NullaryMethodType(resType) => resType
            case _                          => tp
        }

        // for some reason any's members don't show up in subclasses, which
        // we need so 5.<tab> offers asInstanceOf etc.
        private def anyMembers = AnyClass.tpe.nonPrivateMembers
        def anyRefMethodsToShow = Set("isInstanceOf", "asInstanceOf", "toString")

        def tos(sym: Symbol): String = sym.decodedName
        def memberNamed(s: String) = afterTyper(effectiveTp member newTermName(s))
        def hasMethod(s: String) = memberNamed(s).isMethod

        def members  = afterTyper((effectiveTp.nonPrivateMembers.toList ++ anyMembers) filter (_.isPublic))
        def methods  = members.toList filter (_.isMethod)
        def packages = members.toList filter (_.isPackage)
        def aliases  = members.toList filter (_.isAliasType)

        def memberNames  = members map tos
        def methodNames  = methods map tos
        def packageNames = packages map tos
        def aliasNames   = aliases map tos
    }

    object NoTypeCompletion extends TypeMemberCompletion(NoType) {
        override def memberNamed(s: String) = NoSymbol
        override def members = Nil
        override def follow(s: String) = None
    }

    object TypeMemberCompletion {
        def apply(tp: Type, runtimeType: Type, param: NamedParam): TypeMemberCompletion = {
            new TypeMemberCompletion(tp) {
                var upgraded = false
                lazy val upgrade = {
                    intp rebind param
                    intp.reporter.printMessage("\nRebinding stable value %s from %s to %s".format(param.name, tp, param.tpe))
                    upgraded = true
                    new TypeMemberCompletion(runtimeType)
                }
                override def completions = {
                    super.completions ++ upgrade.completions
                }
                override def follow(s: String) = super.follow(s) orElse {
                    if (upgraded) upgrade.follow(s)
                    else None
                }
            }
        }
        def apply(tp: Type): TypeMemberCompletion = {
            if (tp eq NoType) NoTypeCompletion
            else if (tp.typeSymbol.isPackageClass) new PackageCompletion(tp)
            else new TypeMemberCompletion(tp)
        }
        def imported(tp: Type) = new ImportCompletion(tp)
    }

    class TypeMemberCompletion(val tp: Type) extends CompletionAware with CompilerCompletion {
        def excludeEndsWith: List[String] = Nil
        def excludeStartsWith: List[String] = List("<") // <byname>, <repeated>, etc.
        def excludeNames: List[String] = (anyref.methodNames filterNot anyRefMethodsToShow) :+ "_root_"

        def methodSignatureString(sym: Symbol) = {
            IMain stripString afterTyper(new MethodSymbolOutput(sym).methodString())
        }

        def exclude(name: String): Boolean = (
            (name contains "$") ||
            (excludeNames contains name) ||
            (excludeEndsWith exists (name endsWith _)) ||
            (excludeStartsWith exists (name startsWith _))
        )
        def filtered(xs: List[String]) = xs filterNot exclude distinct

        def completions =
            filtered(memberNames)

        override def follow(s: String): Option[CompletionAware] =
            Some(TypeMemberCompletion(memberNamed(s).tpe)) filterNot (_ eq NoTypeCompletion)

        override def toString = "%s (%d members)".format(tp, members.size)
    }

    class PackageCompletion(tp: Type) extends TypeMemberCompletion(tp) {
        override def excludeNames = anyref.methodNames
    }

    class LiteralCompletion(lit: Literal) extends TypeMemberCompletion(lit.value.tpe)

    class ImportCompletion(tp: Type) extends TypeMemberCompletion(tp)

    // user-issued wildcard imports like "import global._" or "import String._"
    private def imported = intp.sessionWildcards map TypeMemberCompletion.imported

    // not for completion but for excluding
    object anyref extends TypeMemberCompletion(AnyRefClass.tpe) {}

    // the unqualified vals/defs/etc visible in the repl
    object ids extends CompletionAware {
        override def completions = intp.unqualifiedIds ++ List("classOf") //, "_root_")
        // now we use the compiler for everything.
        override def follow(id: String): Option[CompletionAware] = {
            if (!completions.contains(id))
                return None

            intp typeOfExpression id match {
                case NoType => None
                case tpe => Some(TypeMemberCompletion(tpe))
            }
        }
    }

    // literal Ints, Strings, etc.
    object literals extends CompletionAware {
        def simpleParse(code: String): Tree = newUnitParser(code).templateStats().last
        def completions = Nil

        override def follow(id: String) = simpleParse(id) match {
            case x: Literal => Some(new LiteralCompletion(x))
            case _          => None
        }
    }

    // top level packages
    object rootClass extends TypeMemberCompletion(RootClass.tpe) {
        override def completions = super.completions :+ "_root_"
        override def follow(id: String) = id match {
            case "_root_" => Some(this)
            case _        => super.follow(id)
        }
    }
    // members of Predef
    object predef extends TypeMemberCompletion(PredefModule.tpe)
    // members of scala.*
    object scalalang extends PackageCompletion(ScalaPackage.tpe)
    // members of java.lang.*
    object javalang extends PackageCompletion(JavaLangPackage.tpe)

    // the list of completion aware objects which should be consulted
    // for top level unqualified, it's too noisy to let much in.
    lazy val topLevelBase: List[CompletionAware] = List(ids, rootClass, predef, scalalang, javalang, literals)
    def topLevel = topLevelBase ++ imported

    def topLevelFor(parsed: Parsed): List[String] =
        topLevel.flatMap(_ completionsFor parsed)

    def completions(input: String): List[String] =
        topLevelFor(Parsed.dotted(input, input.length))

    def completer(): ScalaCompleter = ???
}
