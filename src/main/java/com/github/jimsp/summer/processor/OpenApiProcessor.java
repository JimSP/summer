package com.github.jimsp.summer.processor;

import com.github.jimsp.summer.annotations.Channel;
import com.github.jimsp.summer.annotations.Summer; // Changed from ContractFrom
import com.github.jimsp.summer.annotations.Summer.Mode; // Changed from ContractFrom.Mode
import com.github.jimsp.summer.messaging.Channel as MsgChannel;
import com.google.auto.service.AutoService;
import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;
import com.squareup.javapoet.*;
import org.openapitools.codegen.DefaultGenerator;
import org.openapitools.codegen.config.CodegenConfigurator;
import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.*;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

@SupportedAnnotationTypes("com.github.jimsp.summer.annotations.Summer") // Changed
@SupportedSourceVersion(SourceVersion.RELEASE_17)
@AutoService(Processor.class)
public class OpenApiProcessor extends AbstractProcessor {
    private Filer filer;
    private Messager log;
    private Types types;
    private Elements elems;
    private RoundEnvironment round;

    @Override
    public synchronized void init(ProcessingEnvironment env) {
        super.init(env);
        filer = env.getFiler();
        log = env.getMessager();
        types = env.getTypeUtils();
        elems = env.getElementUtils();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annots, RoundEnvironment rnd) {
        this.round = rnd;
        for (Element marker : rnd.getElementsAnnotatedWith(Summer.class)) { // Changed
            ContractRaw raw = new ContractRaw(marker.getAnnotation(Summer.class)); // Changed
            try {
                Map<String,String> src = generate(raw.spec());
                src.forEach(this::emit);
                patchServiceImpl(marker, raw);
            } catch(Exception ex){
                log.printMessage(Diagnostic.Kind.ERROR, "Processor error: "+ex, marker);
            }
        }
        return true;
    }

    private Map<String,String> generate(String spec) throws Exception {
        FileSystem fs = Jimfs.newFileSystem(Configuration.unix());
        Path out = fs.getPath("/gen");
        CodegenConfigurator cfg = new CodegenConfigurator()
            .setGeneratorName("jaxrs-spec")
            .setInputSpec(Paths.get(spec).toUri().toString())
            .setOutputDir(out.toString())
            .setModelPackage("com.github.jimsp.summer.dto")
            .setApiPackage("com.github.jimsp.summer.api")
            .setInvokerPackage("com.github.jimsp.summer.invoker")
            .setAdditionalProperties(Map.of(
                "interfaceOnly",true,
                "useBeanValidation",true,
                "useLombokAnnotations",true,
                "modelMutable",false,
                "serializationLibrary","jackson"));
        new DefaultGenerator().opts(cfg.toClientOptInput()).generate();
        Map<String,String> map=new HashMap<>();
        Files.walk(out).filter(p->p.toString().endsWith(".java")).forEach(p->{
            try{
                String fq=p.toString().replace(out+"/src/main/java/","").replace('/', '.').replace(".java","");
                map.put(fq,Files.readString(p));
            }catch(IOException e){throw new UncheckedIOException(e);}
        });
        fs.close();
        return map;
    }

    private void emit(String fq,String code){
        try(Writer w=filer.createSourceFile(fq).openWriter()){
            w.write(code);
        } catch(IOException e){
            log.printMessage(Diagnostic.Kind.ERROR,"emit "+fq+": "+e, null);
        }
    }

    private void patchServiceImpl(Element marker, ContractRaw c) throws IOException {
        String res = marker.getSimpleName().toString().replace("Api","").toLowerCase();
        String dtoName = capitalize(res);
        ClassName dto = ClassName.get("com.github.jimsp.summer.dto", dtoName);
        String ifaceFq = "com.github.jimsp.summer.api."+dtoName+"ApiService";
        TypeElement ifaceEl = elems.getTypeElement(ifaceFq);
        if (ifaceEl == null) {
            log.printMessage(Diagnostic.Kind.ERROR, "Interface not found: " + ifaceFq, marker);
            return;
        }
        ExecutableElement m = ifaceEl.getEnclosedElements().stream()
            .filter(e->e.getKind()==ElementKind.METHOD)
            .map(e->(ExecutableElement)e).findFirst().orElse(null);

        if (m == null) {
            log.printMessage(Diagnostic.Kind.ERROR, "No method found in interface: " + ifaceFq, marker);
            return;
        }

        FieldSpec field;
        MethodSpec sendImpl;

        if(c.mode()==com.github.jimsp.summer.annotations.Summer.Mode.SYNC){ // Changed
            ClassName hType = ClassName.get("com.github.jimsp.summer.handlers", dtoName+"Handler");
            field = FieldSpec.builder(hType,"handler",Modifier.PRIVATE)
                .addAnnotation(ClassName.get("jakarta.inject","Inject")).build();
            sendImpl = MethodSpec.overriding(m)
                .addStatement("$T r = handler.handle(body)", dto)
                .addStatement("return $T.ok(r).build()", ClassName.get("jakarta.ws.rs.core","Response")).build();
        } else {
            String chanName = "channel."+c.cluster()+"."+res+"."+m.getSimpleName();
            // The 'current' class name returned by generateWrappers IS the class qualified with 'chanName'
            ClassName currentChannelImplementationClass = generateWrappers(dto, c, chanName, marker);
            field = FieldSpec.builder(currentChannelImplementationClass,"channel",Modifier.PRIVATE)
                .addAnnotation(ClassName.get("jakarta.inject","Inject"))
                // This injects the bean that is qualified as 'chanName'.
                // generateWrappers ensures such a bean (the outermost alias or wrapper) is created.
                .addAnnotation(AnnotationSpec.builder(Channel.class)
                .addMember("value","$S", chanName).build()).build();
            sendImpl = MethodSpec.overriding(m)
                .addStatement("channel.send(body)")
                .addStatement("return $T.accepted().build()", ClassName.get("jakarta.ws.rs.core","Response"))
                .build();
        }

        TypeSpec impl = TypeSpec.classBuilder(dtoName+"ApiServiceImpl")
            .addModifiers(Modifier.PUBLIC)
            .addAnnotation(ClassName.get("jakarta.enterprise.context","ApplicationScoped"))
            .addSuperinterface(ClassName.bestGuess(ifaceFq))
            .addField(field)
            .addMethod(sendImpl)
            .build();
        JavaFile.builder("com.github.jimsp.summer.service", impl).build().writeTo(filer);
    }
