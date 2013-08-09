package org.refptr.iscala

import java.util.UUID

package object msg {
    type MIME = String
    type Data = Map[MIME, String]
    type Metadata = Map[String, String]

    case class Msg(
        idents: List[UUID],
        header: Header,
        parent_header: Header,
        metadata: Metadata,
        content: Content)

    case class Header(
        msg_id: UUID,
        username: String,
        session: UUID,
        msg_type: MsgType)

    object MsgType extends Enumeration {
        type MsgType = Value

        val execute_request,
            execute_reply,
            object_info_request,
            object_info_reply,
            complete_request,
            complete_reply,
            history_request,
            history_reply,
            connect_request,
            connect_reply,
            kernel_info_request,
            kernel_info_reply,
            shutdown_request,
            shutdown_reply,
            stream,
            display_data,
            pyin,
            pyout,
            pyerr,
            status,
            input_request,
            input_reply = Value
    }
    type MsgType = MsgType.MsgType

    sealed trait Content
    sealed trait Request extends Content
    sealed trait Reply extends Content

    case class execute_request(
        // Source code to be executed by the kernel, one or more lines.
        code: String,

        // A boolean flag which, if True, signals the kernel to execute
        // this code as quietly as possible.  This means that the kernel
        // will compile the code with 'exec' instead of 'single' (so
        // sys.displayhook will not fire), forces store_history to be False,
        // and will *not*:
        //   - broadcast exceptions on the PUB socket
        //   - do any logging
        //
        // The default is False.
        silent: Boolean,

        // A boolean flag which, if True, signals the kernel to populate history
        // The default is True if silent is False.  If silent is True, store_history
        // is forced to be False.
        store_history: Boolean,

        // A list of variable names from the user's namespace to be retrieved.
        // What returns is a rich representation of each variable (dict keyed by name).
        // See the display_data content for the structure of the representation data.
        user_variables: List[String],

        // Similarly, a dict mapping names to expressions to be evaluated in the
        // user's dict.
        user_expressions: Map[String, String],

        // Some frontends (e.g. the Notebook) do not support stdin requests. If
        // raw_input is called from code executed from such a frontend, a
        // StdinNotImplementedError will be raised.
        allow_stdin: Boolean) extends Request

    sealed abstract class ExecutionStatus(str: String)
    case object OK extends ExecutionStatus("ok")
    case object Error extends ExecutionStatus("error")
    case object Abort extends ExecutionStatus("abort")

    case class execute_reply(
        // One of: 'ok' OR 'error' OR 'abort'
        status: ExecutionStatus,

        // The global kernel counter that increases by one with each request that
        // stores history.  This will typically be used by clients to display
        // prompt numbers to the user.  If the request did not store history, this will
        // be the current value of the counter in the kernel.
        execution_count: Int,

        // When status is ‘ok’, the following extra fields are present:

        // 'payload' will be a list of payload dicts.
        // Each execution payload is a dict with string keys that may have been
        // produced by the code being executed.  It is retrieved by the kernel at
        // the end of the execution and sent back to the front end, which can take
        // action on it as needed.  See main text for further details.
        payload: List[Map[String, String]],

        // Results for the user_variables and user_expressions.
        user_variables: Map[String, String],
        user_expressions: Map[String, String]) extends Reply

    case class object_info_request(
        // The (possibly dotted) name of the object to be searched in all
        // relevant namespaces
        oname: String,

        // The level of detail desired.  The default (0) is equivalent to typing
        // 'x?' at the prompt, 1 is equivalent to 'x??'.
        detail_level: Int) extends Request

    case class ArgSpec(
        // The names of all the arguments
        args: List[String],
        // The name of the varargs (*args), if any
        varargs: String,
        // The name of the varkw (**kw), if any
        varkw: String,
        // The values (as strings) of all default arguments.  Note
        // that these must be matched *in reverse* with the 'args'
        // list above, since the first positional args have no default
        // value at all.
        defaults: List[String])

    case class object_info_reply(
        // The name the object was requested under
        name: String,

        // Boolean flag indicating whether the named object was found or not.  If
        // it's false, all other fields will be empty.
        found: Boolean,

        // Flags for magics and system aliases
        ismagic: Boolean,
        isalias: Boolean,

        // The name of the namespace where the object was found ('builtin',
        // 'magics', 'alias', 'interactive', etc.)
        namespace: String,

        // The type name will be type.__name__ for normal Python objects, but it
        // can also be a string like 'Magic function' or 'System alias'
        type_name: String,

        // The string form of the object, possibly truncated for length if
        // detail_level is 0
        string_form: String,

        // For objects with a __class__ attribute this will be set
        base_class: String,

        // For objects with a __len__ attribute this will be set
        length: String,

        // If the object is a function, class or method whose file we can find,
        // we give its full path
        file: String,

        // For pure Python callable objects, we can reconstruct the object
        // definition line which provides its call signature.  For convenience this
        // is returned as a single 'definition' field, but below the raw parts that
        // compose it are also returned as the argspec field.
        definition: String,

        // The individual parts that together form the definition string.  Clients
        // with rich display capabilities may use this to provide a richer and more
        // precise representation of the definition line (e.g. by highlighting
        // arguments based on the user's cursor position).  For non-callable
        // objects, this field is empty.
        argspec: ArgSpec,

        // For instances, provide the constructor signature (the definition of
        // the __init__ method):
        init_definition: String,

        // Docstrings: for any object (function, method, module, package) with a
        // docstring, we show it.  But in addition, we may provide additional
        // docstrings.  For example, for instances we will show the constructor
        // and class docstrings as well, if available.
        docstring: String,

        // For instances, provide the constructor and class docstrings
        init_docstring: String,
        class_docstring: String,

        // If it's a callable object whose call method has a separate docstring and
        // definition line:
        call_def: String,
        call_docstring: String,

        // If detail_level was 1, we also try to find the source code that
        // defines the object, if possible.  The string 'None' will indicate
        // that no source was found.
        source: String) extends Reply

    case class complete_request(
        // The text to be completed, such as 'a.is'
        // this may be an empty string if the frontend does not do any lexing,
        // in which case the kernel must figure out the completion
        // based on 'line' and 'cursor_pos'.
        text: String,

        // The full line, such as 'print a.is'.  This allows completers to
        // make decisions that may require information about more than just the
        // current word.
        line: String,

        // The entire block of text where the line is.  This may be useful in the
        // case of multiline completions where more context may be needed.  Note: if
        // in practice this field proves unnecessary, remove it to lighten the
        // messages.

        block: Option[String],

        // The position of the cursor where the user hit 'TAB' on the line.
        cursor_pos: Int) extends Request

    case class complete_reply(
        // The list of all matches to the completion request, such as
        // ['a.isalnum', 'a.isalpha'] for the above example.
        matches: List[String],

        // the substring of the matched text
        // this is typically the common prefix of the matches,
        // and the text that is already in the block that would be replaced by the full completion.
        // This would be 'a.is' in the above example.
        text: String,

        // status should be 'ok' unless an exception was raised during the request,
        // in which case it should be 'error', along with the usual error message content
        // in other messages.
        status: ExecutionStatus) extends Reply

    sealed abstract class HistAccessType(str: String)
    case object Range extends HistAccessType("range")
    case object Tail extends HistAccessType("tail")
    case object Search extends HistAccessType("search")

    case class history_request(
        // If True, also return output history in the resulting dict.
        output: Boolean,

        // If True, return the raw input history, else the transformed input.
        raw: Boolean,

        // So far, this can be 'range', 'tail' or 'search'.
        hist_access_type: HistAccessType,

        // If hist_access_type is 'range', get a range of input cells. session can
        // be a positive session number, or a negative number to count back from
        // the current session.
        session: Int,

        // start and stop are line numbers within that session.
        start: Int,
        stop: Int,

        // If hist_access_type is 'tail' or 'search', get the last n cells.
        n: Int,

        // If hist_access_type is 'search', get cells matching the specified glob
        // pattern (with * and ? as wildcards).
        pattern: String,

        // If hist_access_type is 'search' and unique is true, do not
        // include duplicated history.  Default is false.
        unique: Boolean) extends Request

    case class history_reply(
        // A list of 3 tuples, either:
        // (session, line_number, input) or
        // (session, line_number, (input, output)),
        // depending on whether output was False or True, respectively.
        history: List[(String, Int, Either[String, (String, String)])]) extends Reply

    case class connect_request() extends Request

    case class connect_reply(
        // The port the shell ROUTER socket is listening on.
        shell_port: Int,
        // The port the PUB socket is listening on.
        iopub_port: Int,
        // The port the stdin ROUTER socket is listening on.
        stdin_port: Int,
        // The port the heartbeat socket is listening on.
        hb_port: Int) extends Reply

    case class kernel_info_request() extends Request

    case class kernel_info_reply(
        // Version of messaging protocol (mandatory).
        // The first integer indicates major version.  It is incremented when
        // there is any backward incompatible change.
        // The second integer indicates minor version.  It is incremented when
        // there is any backward compatible change.
        protocol_version: (Int, Int),

        // IPython version number (optional).
        // Non-python kernel backend may not have this version number.
        // The last component is an extra field, which may be 'dev' or
        // 'rc1' in development version.  It is an empty string for
        // released version.
        ipython_version: (Int, Int, Int, String),

        // Language version number (mandatory).
        // It is Python version number (e.g., [2, 7, 3]) for the kernel
        // included in IPython.
        language_version: List[Int],

        // Programming language in which kernel is implemented (mandatory).
        // Kernel included in IPython returns 'python'.
        language: String) extends Reply

    case class shutdown_request(
        // whether the shutdown is final, or precedes a restart
        restart: Boolean) extends Request

    case class shutdown_reply(
        // whether the shutdown is final, or precedes a restart
        restart: Boolean) extends Reply

    case class stream(
        // The name of the stream is one of 'stdout', 'stderr'
        name: String,

        // The data is an arbitrary string to be written to that stream
        data: String) extends Reply

    case class display_data(
        // Who create the data
        source: String,

        // The data dict contains key/value pairs, where the kids are MIME
        // types and the values are the raw data of the representation in that
        // format.
        data: Data,

        // Any metadata that describes the data
        metadata: Metadata) extends Reply

    case class pyin(
        // Source code to be executed, one or more lines
        code: String,

        // The counter for this execution is also provided so that clients can
        // display it, since IPython automatically creates variables called _iN
        // (for input prompt In[N]).
        execution_count: Int) extends Reply

    case class pyout(
        // The counter for this execution is also provided so that clients can
        // display it, since IPython automatically creates variables called _N
        // (for prompt N).
        execution_count: Int,

        // data and metadata are identical to a display_data message.
        // the object being displayed is that passed to the display hook,
        // i.e. the *result* of the execution.
        data: Data,
        metadata: Metadata) extends Reply

    case class pyerr(
        // Similar content to the execute_reply messages for the 'error' case,
        // except the 'status' field is omitted.
    ) extends Reply

    sealed abstract class ExecutionState(str: String)
    case object Busy extends ExecutionState("busy")
    case object Idle extends ExecutionState("idle")
    case object Starting extends ExecutionState("starting")

    case class status(
        // When the kernel starts to execute code, it will enter the 'busy'
        // state and when it finishes, it will enter the 'idle' state.
        // The kernel will publish state 'starting' exactly once at process startup.
        execution_state: ExecutionState) extends Reply

    case class input_request(
        prompt: String) extends Request

    case class input_reply(
        value: String) extends Reply
}
