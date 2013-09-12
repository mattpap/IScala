package org.refptr.iscala.macros.json

import play.api.libs.json.{Reads,Writes,Format}

import scala.reflect.macros.Context

trait JsonImpl {
    def reads[A]:  Reads[A]  = macro JsMacroImpl.readsImpl[A]
    def writes[A]: Writes[A] = macro JsMacroImpl.sealedWritesImpl[A]
    def format[A]: Format[A] = macro JsMacroImpl.formatImpl[A]
}

object JsMacroImpl {
    private def debug(msg: => Any) = {
        if (false) {
            println(msg.toString.split("\n").map("[macro] " + _).mkString("\n"))
        }
    }

    /* JSON writer for sealed traits.
     *
     * This macro generates code equivalent to:
     * ```
     * new Writes[T] {
     *     val $writes$T_1 = Json.writes[T_1]
     *     ...
     *     val $writes$T_n = Json.writes[T_n]
     *
     *     def writes(obj: T) = (obj match {
     *         case o: T_1 => $writes$T_1.writes(o)
     *         ...
     *         case o: T_n => $writes$T_n.writes(o)
     *     }) ++ JsObject(List(
     *         ("field_1", Json.toJson(obj.field_1)),
     *         ...
     *         ("field_n", Json.toJson(obj.field_n))))
     * }
     * ```
     *
     * `T` is a sealed trait with case subclasses `T_1`, ... `T_n`. Fields `field_1`,
     * ..., `field_n` are `T`'s vals that don't appear in `T_i` constructors.
     */
    def sealedWritesImpl[T: c.WeakTypeTag](c: Context): c.Expr[Writes[T]] = {
        import c.universe._

        val tpe = weakTypeOf[T]
        val symbol = tpe.typeSymbol

        if (!symbol.isClass) {
            c.abort(c.enclosingPosition, "expected a class or trait")
        }

        val cls = symbol.asClass

        if (!cls.isTrait) {
            writesImpl(c)
        } else if (!cls.isSealed) {
            c.abort(c.enclosingPosition, "expected a sealed trait")
        } else {
            val children = cls.knownDirectSubclasses.toList

            if (children.isEmpty) {
                c.abort(c.enclosingPosition, "trait has no subclasses")
            } else if (!children.forall(_.isClass) || !children.map(_.asClass).forall(_.isCaseClass)) {
                c.abort(c.enclosingPosition, "all children must be case classes")
            } else {
                val named = children.map { child =>
                    (child, newTermName("$writes$" + child.name.toString))
                }

                val valDefs = named.map { case (child, name) =>
                    q"val $name = play.api.libs.json.Json.writes[$child]"
                }

                val caseDefs = named.map { case (child, name) =>
                    CaseDef(
                        Bind(newTermName("o"), Typed(Ident(nme.WILDCARD),
                             Ident(child))),
                        EmptyTree,
                        q"$name.writes(o)")
                }

                val names = children.flatMap(
                    _.typeSignature
                     .declaration(nme.CONSTRUCTOR)
                     .asMethod
                     .paramss(0)
                     .map(_.name.toString)
                 ).toSet

                val fieldNames = cls.typeSignature
                   .declarations
                   .toList
                   .filter(_.isMethod)
                   .map(_.asMethod)
                   .filter(_.isStable)
                   .filter(_.isPublic)
                   .map(_.name.toString)
                   .filterNot(names contains _)

                val fieldDefs = fieldNames.map { fieldName =>
                    val name = newTermName(fieldName)
                    q"($fieldName, play.api.libs.json.Json.toJson(obj.$name))"
                }

                val matchDef = Match(q"obj", caseDefs)

                val expr = c.Expr[Writes[T]](
                    q"""
                    new Writes[$symbol] {
                        ..$valDefs

                        def writes(obj: $symbol) =
                            $matchDef ++ play.api.libs.json.JsObject(List(..$fieldDefs))
                    }
                    """)

                debug(show(expr))
                expr
            }
        }
    }

  def readsImpl[A: c.WeakTypeTag](c: Context): c.Expr[Reads[A]] = {
    import c.universe._
    import c.universe.Flag._

    val companioned = weakTypeOf[A].typeSymbol
    val companionSymbol = companioned.companionSymbol
    val companionType = companionSymbol.typeSignature

    val jsPathSelect = q"play.api.libs.json.JsPath"
    val readsSelect = q"play.api.libs.json.Reads"
    val unliftIdent = q"play.api.libs.functional.syntax.unlift"
    val lazyHelperSelect = q"play.api.libs.json.util.LazyHelper"

    companionType.declaration(stringToTermName("unapply")) match {
      case NoSymbol => c.abort(c.enclosingPosition, "No unapply function found")
      case s =>
        val unapply = s.asMethod
        val unapplyReturnTypes = unapply.returnType match {
          case TypeRef(_, _, args) =>
            args.head match {
              case t @ TypeRef(_, _, Nil) => Some(List(t))
              case t @ TypeRef(_, _, args) =>
                if (t <:< typeOf[Option[_]]) Some(List(t))
                else if (t <:< typeOf[Seq[_]]) Some(List(t))
                else if (t <:< typeOf[Set[_]]) Some(List(t))
                else if (t <:< typeOf[Map[_, _]]) Some(List(t))
                else if (t <:< typeOf[Product]) Some(args)
              case _ => None
            }
          case _ => None
        }

        //println("Unapply return type:" + unapply.returnType)

        companionType.declaration(stringToTermName("apply")) match {
          case NoSymbol => c.abort(c.enclosingPosition, "No apply function found")
          case s =>
            // searches apply method corresponding to unapply
            val applies = s.asMethod.alternatives
            val apply = applies.collectFirst {
              case (apply: MethodSymbol) if (apply.paramss.headOption.map(_.map(_.asTerm.typeSignature)) == unapplyReturnTypes) => apply
            }
            apply match {
              case Some(apply) =>
                //println("apply found:" + apply)
                val params = apply.paramss.head //verify there is a single parameter group

                val inferedImplicits = params.map(_.typeSignature).map { implType =>

                  val (isRecursive, tpe) = implType match {
                    case TypeRef(_, t, args) =>
                      // Option[_] needs special treatment because we need to use XXXOpt
                      if (implType.typeConstructor <:< typeOf[Option[_]].typeConstructor)
                        (args.exists { a => a.typeSymbol == companioned }, args.head)
                      else (args.exists { a => a.typeSymbol == companioned }, implType)
                    case TypeRef(_, t, _) =>
                      (false, implType)
                  }

                  // builds reads implicit from expected type
                  val neededImplicitType = appliedType(weakTypeOf[Reads[_]].typeConstructor, tpe :: Nil)
                  // infers implicit
                  val neededImplicit = c.inferImplicitValue(neededImplicitType)
                  (implType, neededImplicit, isRecursive, tpe)
                }

                // if any implicit is missing, abort
                // else goes on
                inferedImplicits.collect { case (t, impl, rec, _) if (impl == EmptyTree && !rec) => t } match {
                  case List() =>
                    val namedImplicits = params.map(_.name).zip(inferedImplicits)
                    //println("Found implicits:"+namedImplicits)

                    val helperMember = Select(This(tpnme.EMPTY), newTermName("lazyStuff"))

                    var hasRec = false

                    // combines all reads into CanBuildX
                    val canBuild = namedImplicits.map {
                      case (name, (t, impl, rec, tpe)) =>
                        // inception of (__ \ name).read(impl)
                        val jspathTree = q"$jsPathSelect \ ${name.decoded}"

                        if (!rec) {
                          if (t.typeConstructor <:< typeOf[Option[_]].typeConstructor)
                            q"$jspathTree.readNullable($impl)"
                          else
                            q"$jspathTree.read($impl)"
                        } else {
                          hasRec = true
                          if (t.typeConstructor <:< typeOf[Option[_]].typeConstructor)
                            q"$jspathTree.readNullable($jsPathSelect.lazyRead($helperMember))"
                          else {
                            val arg =
                              if (tpe.typeConstructor <:< typeOf[List[_]].typeConstructor)
                                q"$readsSelect.list($helperMember)"
                              else if (tpe.typeConstructor <:< typeOf[Set[_]].typeConstructor)
                                q"$readsSelect.set($helperMember)"
                              else if (tpe.typeConstructor <:< typeOf[Seq[_]].typeConstructor)
                                q"$readsSelect.seq($helperMember)"
                              else if (tpe.typeConstructor <:< typeOf[Map[_, _]].typeConstructor)
                                q"$readsSelect.map($helperMember)"
                              else
                                helperMember
                            q"$jspathTree.lazyRead($arg)"
                          }
                        }
                    }.reduceLeft((acc, r) => q"$acc and $r")

                    // builds the final Reads using apply method
                    val applyMethod =
                      Function(
                        params.foldLeft(List[ValDef]())((l, e) =>
                          l :+ ValDef(Modifiers(PARAM), newTermName(e.name.encoded), TypeTree(), EmptyTree)
                        ),
                        Apply(
                          Select(Ident(companionSymbol.name), newTermName("apply")),
                          params.foldLeft(List[Tree]())((l, e) =>
                            l :+ Ident(newTermName(e.name.encoded))
                          )
                        )
                      )

                    val unapplyMethod = Apply(
                      unliftIdent,
                      List(
                        Select(Ident(companionSymbol.name), unapply.name)
                      )
                    )

                    // if case class has one single field, needs to use inmap instead of canbuild.apply
                    val finalTree = if (params.length > 1) {
                      Apply(
                        Select(canBuild, newTermName("apply")),
                        List(applyMethod)
                      )
                    } else {
                      Apply(
                        Select(canBuild, newTermName("map")),
                        List(applyMethod)
                      )
                    }
                    //println("finalTree: "+finalTree)

                    if (!hasRec) {
                      c.Expr[Reads[A]](q"{ import play.api.libs.functional.syntax._; $finalTree }")
                    } else {
                      val helper = newTermName("helper")
                      val helperVal = ValDef(
                        Modifiers(),
                        helper,
                        TypeTree(weakTypeOf[play.api.libs.json.util.LazyHelper[Reads, A]]),
                        Apply(lazyHelperSelect, List(finalTree))
                      )

                      val block = Select(
                        Block(List(
                          q"import play.api.libs.functional.syntax._",
                          ClassDef(
                            Modifiers(Flag.FINAL),
                            newTypeName("$anon"),
                            List(),
                            Template(
                              List(
                                AppliedTypeTree(
                                  lazyHelperSelect,
                                  List(
                                    Ident(weakTypeOf[Reads[A]].typeSymbol),
                                    Ident(weakTypeOf[A].typeSymbol)
                                  )
                                )
                              ),
                              emptyValDef,
                              List(
                                DefDef(
                                  Modifiers(),
                                  nme.CONSTRUCTOR,
                                  List(),
                                  List(List()),
                                  TypeTree(),
                                  Block(
                                    List(),
                                    Apply(
                                      Select(Super(This(tpnme.EMPTY), tpnme.EMPTY), nme.CONSTRUCTOR),
                                      List()
                                    )
                                  )
                                ),
                                ValDef(
                                  Modifiers(Flag.OVERRIDE | Flag.LAZY),
                                  newTermName("lazyStuff"),
                                  AppliedTypeTree(Ident(weakTypeOf[Reads[A]].typeSymbol), List(TypeTree(weakTypeOf[A]))),
                                  finalTree
                                )
                              )
                            )
                          )),
                          Apply(Select(New(Ident(newTypeName("$anon"))), nme.CONSTRUCTOR), List())
                        ),
                        newTermName("lazyStuff")
                      )

                      //println("block:"+block)

                      c.Expr[Reads[A]](block)
                    }
                  case l => c.abort(c.enclosingPosition, s"No implicit Reads for ${l.mkString(", ")} available.")
                }

              case None => c.abort(c.enclosingPosition, "No apply function found matching unapply return types")
            }

        }
    }
  }

  def writesImpl[A: c.WeakTypeTag](c: Context): c.Expr[Writes[A]] = {
    import c.universe._
    import c.universe.Flag._

    val companioned = weakTypeOf[A].typeSymbol
    val companionSymbol = companioned.companionSymbol
    val companionType = companionSymbol.typeSignature

    val jsPathSelect = q"play.api.libs.json.JsPath"
    val writesSelect = q"play.api.libs.json.Writes"
    val unliftIdent = q"play.api.libs.functional.syntax.unlift"
    val lazyHelperSelect = q"play.api.libs.json.util.LazyHelper"

    companionType.declaration(stringToTermName("unapply")) match {
      case NoSymbol => c.abort(c.enclosingPosition, "No unapply function found")
      case s =>
        val unapply = s.asMethod
        val unapplyReturnTypes = unapply.returnType match {
          case TypeRef(_, _, args) =>
            args.head match {
              case t @ TypeRef(_, _, Nil) => Some(List(t))
              case t @ TypeRef(_, _, args) =>
                if (t <:< typeOf[Option[_]]) Some(List(t))
                else if (t <:< typeOf[Seq[_]]) Some(List(t))
                else if (t <:< typeOf[Set[_]]) Some(List(t))
                else if (t <:< typeOf[Map[_, _]]) Some(List(t))
                else if (t <:< typeOf[Product]) Some(args)
              case _ => None
            }
          case _ => None
        }

        //println("Unapply return type:" + unapplyReturnTypes)

        companionType.declaration(stringToTermName("apply")) match {
          case NoSymbol => c.abort(c.enclosingPosition, "No apply function found")
          case s =>
            // searches apply method corresponding to unapply
            val applies = s.asMethod.alternatives
            val apply = applies.collectFirst {
              case (apply: MethodSymbol) if (apply.paramss.headOption.map(_.map(_.asTerm.typeSignature)) == unapplyReturnTypes) => apply
            }
            apply match {
              case Some(apply) =>
                //println("apply found:" + apply)
                val params = apply.paramss.head //verify there is a single parameter group

                val inferedImplicits = params.map(_.typeSignature).map { implType =>

                  val (isRecursive, tpe) = implType match {
                    case TypeRef(_, t, args) =>
                      // Option[_] needs special treatment because we need to use XXXOpt
                      if (implType.typeConstructor <:< typeOf[Option[_]].typeConstructor)
                        (args.exists { a => a.typeSymbol == companioned }, args.head)
                      else (args.exists { a => a.typeSymbol == companioned }, implType)
                    case TypeRef(_, t, _) =>
                      (false, implType)
                  }

                  // builds reads implicit from expected type
                  val neededImplicitType = appliedType(weakTypeOf[Writes[_]].typeConstructor, tpe :: Nil)
                  // infers implicit
                  val neededImplicit = c.inferImplicitValue(neededImplicitType)
                  (implType, neededImplicit, isRecursive, tpe)
                }

                // if any implicit is missing, abort
                // else goes on
                inferedImplicits.collect { case (t, impl, rec, _) if (impl == EmptyTree && !rec) => t } match {
                  case List() =>
                    val namedImplicits = params.map(_.name).zip(inferedImplicits)
                    //println("Found implicits:"+namedImplicits)

                    val helperMember = Select(This(tpnme.EMPTY), newTermName("lazyStuff"))

                    var hasRec = false

                    // combines all writes into CanBuildX
                    val canBuild = namedImplicits.map {
                      case (name, (t, impl, rec, tpe)) =>
                        // inception of (__ \ name).write(impl)
                        val jspathTree = q"$jsPathSelect \ ${name.decoded}"

                        if (!rec) {
                          if (t.typeConstructor <:< typeOf[Option[_]].typeConstructor)
                            q"$jspathTree.writeNullable($impl)"
                          else
                            q"$jspathTree.write($impl)"
                        } else {
                          hasRec = true
                          if (t.typeConstructor <:< typeOf[Option[_]].typeConstructor)
                            q"$jspathTree.writeNullable($jsPathSelect.lazyWrite($helperMember))"
                          else {
                            val arg =
                              if (tpe.typeConstructor <:< typeOf[List[_]].typeConstructor)
                                q"$writesSelect.list($helperMember)"
                              else if (tpe.typeConstructor <:< typeOf[Set[_]].typeConstructor)
                                q"$writesSelect.set($helperMember)"
                              else if (tpe.typeConstructor <:< typeOf[Seq[_]].typeConstructor)
                                q"$writesSelect.seq($helperMember)"
                              else if (tpe.typeConstructor <:< typeOf[Map[_, _]].typeConstructor)
                                q"$writesSelect.map($helperMember)"
                              else
                                helperMember
                            q"$jspathTree.lazyWrite($arg)"
                          }
                        }
                    }.reduceLeft((acc, r) => q"$acc and $r")

                    // builds the final Writes using apply method
                    //val applyMethod = Ident( companionSymbol.name )
                    val applyMethod =
                      Function(
                        params.foldLeft(List[ValDef]())((l, e) =>
                          l :+ ValDef(Modifiers(PARAM), newTermName(e.name.encoded), TypeTree(), EmptyTree)
                        ),
                        Apply(
                          Select(Ident(companionSymbol.name), newTermName("apply")),
                          params.foldLeft(List[Tree]())((l, e) =>
                            l :+ Ident(newTermName(e.name.encoded))
                          )
                        )
                      )

                    val unapplyMethod = Apply(
                      unliftIdent,
                      List(
                        Select(Ident(companionSymbol.name), unapply.name)
                      )
                    )

                    // if case class has one single field, needs to use inmap instead of canbuild.apply
                    val finalTree = if (params.length > 1) {
                      Apply(
                        Select(canBuild, newTermName("apply")),
                        List(unapplyMethod)
                      )
                    } else {
                      Apply(
                        Select(canBuild, newTermName("contramap")),
                        List(unapplyMethod)
                      )
                    }
                    //println("finalTree: "+finalTree)

                    if (!hasRec) {
                      c.Expr[Writes[A]](q"{ import play.api.libs.functional.syntax._; $finalTree }")
                    } else {
                      val helper = newTermName("helper")
                      val helperVal = ValDef(
                        Modifiers(),
                        helper,
                        TypeTree(weakTypeOf[play.api.libs.json.util.LazyHelper[Writes, A]]),
                        Apply(lazyHelperSelect, List(finalTree))
                      )

                      val block = Select(
                        Block(List(
                          q"import play.api.libs.functional.syntax._",
                          ClassDef(
                            Modifiers(Flag.FINAL),
                            newTypeName("$anon"),
                            List(),
                            Template(
                              List(
                                AppliedTypeTree(
                                  lazyHelperSelect,
                                  List(
                                    Ident(weakTypeOf[Writes[A]].typeSymbol),
                                    Ident(weakTypeOf[A].typeSymbol)
                                  )
                                )
                              ),
                              emptyValDef,
                              List(
                                DefDef(
                                  Modifiers(),
                                  nme.CONSTRUCTOR,
                                  List(),
                                  List(List()),
                                  TypeTree(),
                                  Block(
                                    List(),
                                    Apply(
                                      Select(Super(This(tpnme.EMPTY), tpnme.EMPTY), nme.CONSTRUCTOR),
                                      List()
                                    )
                                  )
                                ),
                                ValDef(
                                  Modifiers(Flag.OVERRIDE | Flag.LAZY),
                                  newTermName("lazyStuff"),
                                  AppliedTypeTree(Ident(weakTypeOf[Writes[A]].typeSymbol), List(TypeTree(weakTypeOf[A]))),
                                  finalTree
                                )
                              )
                            )
                          )),
                          Apply(Select(New(Ident(newTypeName("$anon"))), nme.CONSTRUCTOR), List())
                        ),
                        newTermName("lazyStuff")
                      )

                      //println("block:"+block)

                      /*val reif = reify(
                        new play.api.libs.json.util.LazyHelper[Format, A] {
                          override lazy val lazyStuff: Format[A] = null
                        }
                      )
                      //println("RAW:"+showRaw(reif.tree, printKinds = true))*/
                      c.Expr[Writes[A]](block)
                    }
                  case l => c.abort(c.enclosingPosition, s"No implicit Writes for ${l.mkString(", ")} available.")
                }

              case None => c.abort(c.enclosingPosition, "No apply function found matching unapply parameters")
            }

        }
    }
  }

    def formatImpl[A: c.WeakTypeTag](c: Context): c.Expr[Format[A]] = {
        import c.universe._
        c.Expr[Format[A]](q"play.api.libs.json.Format(${readsImpl[A](c)}, ${writesImpl[A](c)})")
    }
}
