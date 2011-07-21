/*
 * Copyright (c) 2011, The Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person
 * obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without
 * restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
 * OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 * OTHER DEALINGS IN THE SOFTWARE.
 */

package org.broadinstitute.sting.utils.help;

import com.sun.javadoc.*;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapper;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import org.broadinstitute.sting.commandline.*;
import org.broadinstitute.sting.gatk.CommandLineExecutable;
import org.broadinstitute.sting.gatk.CommandLineGATK;
import org.broadinstitute.sting.gatk.walkers.Walker;
import org.broadinstitute.sting.utils.Utils;
import org.broadinstitute.sting.utils.classloader.JVMUtils;
import org.broadinstitute.sting.utils.exceptions.ReviewedStingException;
import scala.reflect.Print;
import sun.tools.java.ClassNotFound;

import java.io.*;
import java.lang.reflect.Field;
import java.util.*;

/**
 *
 */
public class GATKDoclet extends ResourceBundleExtractorDoclet {
    RootDoc rootDoc;

    /**
     * Extracts the contents of certain types of javadoc and adds them to an XML file.
     * @param rootDoc The documentation root.
     * @return Whether the JavaDoc run succeeded.
     * @throws java.io.IOException if output can't be written.
     */
    public static boolean start(RootDoc rootDoc) throws IOException {
        GATKDoclet doclet = new GATKDoclet();
        //PrintStream out = doclet.loadData(rootDoc, false);
        doclet.processDocs(rootDoc, null);
        return true;
    }

    public static int optionLength(String option) {
        return ResourceBundleExtractorDoclet.optionLength(option);
    }

    @Override
    protected void processDocs(RootDoc rootDoc, PrintStream ignore) {
        // setup the global access to the root
        this.rootDoc = rootDoc;

        try {
            /* ------------------------------------------------------------------- */
            /* You should do this ONLY ONCE in the whole application life-cycle:   */

            Configuration cfg = new Configuration();
            // Specify the data source where the template files come from.
            // Here I set a file directory for it:
            cfg.setDirectoryForTemplateLoading(new File("settings/helpTemplates/"));
            // Specify how templates will see the data-model. This is an advanced topic...
            // but just use this:
            cfg.setObjectWrapper(new DefaultObjectWrapper());

            List<Map<String, Object>> indexData = new ArrayList<Map<String, Object>>();
            for ( ClassDoc doc : rootDoc.classes() ) {
                if ( ResourceBundleExtractorDoclet.isWalker(doc) ) { //  && getClassName(doc).contains("UGCalcLikelihoods")) {
                    System.out.printf("Walker class %s%n", doc);
                    indexData.add(processWalkerDocs(cfg, doc));
                }
            }
            processWalkerIndex(indexData,cfg);
        } catch ( FileNotFoundException e ) {
            throw new RuntimeException(e);
        } catch ( IOException e ) {
            throw new RuntimeException(e);
        }
    }

    private void processWalkerIndex(List<Map<String, Object>> indexData, Configuration cfg) throws IOException {
        /* Get or create a template */
        Template temp = cfg.getTemplate("walker.index.template.html");

        /* Merge data-model with template */
        Writer out = new OutputStreamWriter(new FileOutputStream(new File("testdoc/index.html")));
        try {
            Map<String, Object> root = new HashMap<String, Object>();
            root.put("walkers", indexData);
            temp.process(root, out);
            out.flush();
        } catch ( TemplateException e ) {
            throw new ReviewedStingException("Failed to create GATK documentation", e);
        }
    }

    private Map<String, Object> processWalkerDocs(Configuration cfg, ClassDoc doc) throws IOException {
        // Create the root hash
        Map root = buildWalkerDataModel(doc);

        /* Get or create a template */
        Template temp = cfg.getTemplate("walker.template.html");

        /* Merge data-model with template */
        File outputFile = new File(getClassName(doc).replace(".", "_") + ".html");
        File outputPath = new File("testdoc/" + outputFile);
        try {
            Writer out = new OutputStreamWriter(new FileOutputStream(outputPath));
            temp.process(root, out);
            out.flush();
        } catch ( TemplateException e ) {
            throw new ReviewedStingException("Failed to create GATK documentation", e);
        }

        // add index data
        Map<String, Object> indexData = new HashMap<String, Object>();
        indexData.put("filename", outputFile.toString());
        indexData.put("name", doc.name());
        indexData.put("summary", root.get("summary"));
        return indexData;
    }


    private Map buildWalkerDataModel(ClassDoc classdoc) {
        Map<String, Object> root = new HashMap<String, Object>();

        root.put("name", classdoc.name());

        // Extract overrides from the doc tags.
        StringBuilder summaryBuilder = new StringBuilder();
        for(Tag tag: classdoc.firstSentenceTags())
             summaryBuilder.append(tag.text());
        root.put("summary", summaryBuilder.toString());
        root.put("description", classdoc.commentText());

        for(Tag tag: classdoc.tags()) {
            root.put(tag.name(), tag.text());
        }

        ParsingEngine parsingEngine = createStandardGATKParsingEngine();
//        for (ArgumentDefinition argumentDefinition : parsingEngine.argumentDefinitions )
//            System.out.println(argumentDefinition);

        Map<String, List<Object>> args = new HashMap<String, List<Object>>();
        root.put("arguments", args);
        args.put("all", new ArrayList<Object>());
        args.put("required", new ArrayList<Object>());
        args.put("optional", new ArrayList<Object>());
        args.put("hidden", new ArrayList<Object>());
        args.put("depreciated", new ArrayList<Object>());
        try {
            for ( ArgumentSource argumentSource : parsingEngine.extractArgumentSources(getClassForDoc(classdoc)) ) {
                ArgumentDefinition argDef = argumentSource.createArgumentDefinitions().get(0);
                FieldDoc fieldDoc = getFieldDoc(classdoc, argumentSource.field.getName());
                GATKDoc doc = docForArgument(fieldDoc, argDef); // todo -- why can you have multiple ones?
                String kind = "optional";
                if ( argumentSource.isRequired() ) kind = "required";
                else if ( argumentSource.isHidden() ) kind = "hidden";
                else if ( argumentSource.isDeprecated() ) kind = "depreciated";
                args.get(kind).add(doc.toDataModel());
                args.get("all").add(doc.toDataModel());
                System.out.printf("Processing %s%n", argumentSource);
            }
        } catch ( ClassNotFoundException e ) {
            throw new RuntimeException(e);
        }

        System.out.printf("Root is %s%n", root);
        return root;
    }

//    protected String withDefault(String val, String def) {
//        return val == null ? def : val;
//    }

    protected ParsingEngine createStandardGATKParsingEngine() {
        CommandLineProgram clp = new CommandLineGATK();
        try {
            CommandLineProgram.start(clp, new String[]{}, true);
            return clp.parser;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private FieldDoc getFieldDoc(ClassDoc classDoc, String name) {
        return getFieldDoc(classDoc, name, true);
    }

    private FieldDoc getFieldDoc(ClassDoc classDoc, String name, boolean primary) {
        System.out.printf("Looking for %s in %s%n", name, classDoc.name());
        for ( FieldDoc fieldDoc : classDoc.fields(false) ) {
            System.out.printf("fieldDoc " + fieldDoc + " name " + fieldDoc.name());
            if ( fieldDoc.name().equals(name) )
                return fieldDoc;

            Field field = getFieldForFieldDoc(fieldDoc);
            if ( field.isAnnotationPresent(ArgumentCollection.class) ) {
                ClassDoc typeDoc = this.rootDoc.classNamed(fieldDoc.type().qualifiedTypeName());
                if ( typeDoc == null )
                    throw new ReviewedStingException("Tried to get javadocs for ArgumentCollection field " + fieldDoc + " but could't find the class in the RootDoc");
                else {
                    FieldDoc result = getFieldDoc(typeDoc, name, false);
                    if ( result != null )
                        return result;
                    // else keep searching
                }
            }
        }

        // if we didn't find it here, wander up to the superclass to find the field
        if ( classDoc.superclass() != null ) {
            return getFieldDoc(classDoc.superclass(), name, false);
        }

        if ( primary )
            throw new RuntimeException("No field found for expected field " + name);
        else
            return null;
    }

    protected GATKDoc docForArgument(FieldDoc fieldDoc, ArgumentDefinition def) {
        final String name = def.fullName != null ? "--" + def.fullName : "-" + def.shortName;
        GATKDoc doc = new GATKDoc(GATKDoc.DocType.WALKER_ARG, name);

        if ( def.fullName != null && def.shortName != null)
            doc.addSynonym("-" + def.shortName);

        doc.addTag("required", def.required ? "yes" : "no");
        doc.addTag("type", def.argumentType.getSimpleName());
        if ( def.doc != null ) doc.setSummary(def.doc);

        List<String> attributes = new ArrayList<String>();
        attributes.add(def.ioType.annotationClass.getSimpleName());
        if ( def.required ) attributes.add("required");
        if ( def.isFlag ) attributes.add("flag");
        if ( def.isHidden ) attributes.add("hidden");
        doc.addTag("attributes", Utils.join(",", attributes));

        // todo -- need depreciated value

        doc.addTag("options", def.validOptions == null ? GATKDoc.NA_STRING : Utils.join(",", def.validOptions));

        doc.setFulltext(fieldDoc.commentText());

        return doc;
    }
}
