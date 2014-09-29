package org.hl7.fhir.instance.utils;

/*
Copyright (c) 2011+, HL7, Inc
All rights reserved.

Redistribution and use in source and binary forms, with or without modification, 
are permitted provided that the following conditions are met:

 * Redistributions of source code must retain the above copyright notice, this 
   list of conditions and the following disclaimer.
 * Redistributions in binary form must reproduce the above copyright notice, 
   this list of conditions and the following disclaimer in the documentation 
   and/or other materials provided with the distribution.
 * Neither the name of HL7 nor the names of its contributors may be used to 
   endorse or promote products derived from this software without specific 
   prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND 
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED 
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. 
IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, 
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT 
NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR 
PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, 
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) 
ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
POSSIBILITY OF SUCH DAMAGE.

*/

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hl7.fhir.instance.model.AtomEntry;
import org.hl7.fhir.instance.model.DateAndTime;
import org.hl7.fhir.instance.model.UriType;
import org.hl7.fhir.instance.model.ValueSet;
import org.hl7.fhir.instance.model.ValueSet.ConceptDefinitionComponent;
import org.hl7.fhir.instance.model.ValueSet.ConceptReferenceComponent;
import org.hl7.fhir.instance.model.ValueSet.ConceptSetComponent;
import org.hl7.fhir.instance.model.ValueSet.ConceptSetFilterComponent;
import org.hl7.fhir.instance.model.ValueSet.FilterOperator;
import org.hl7.fhir.instance.model.ValueSet.ValueSetComposeComponent;
import org.hl7.fhir.instance.model.ValueSet.ValueSetExpansionContainsComponent;
import org.hl7.fhir.utilities.Utilities;

public class ValueSetExpanderSimple implements ValueSetExpander {

  private WorkerContext context;
  private List<ValueSetExpansionContainsComponent> codes = new ArrayList<ValueSet.ValueSetExpansionContainsComponent>();
  private Map<String, ValueSetExpansionContainsComponent> map = new HashMap<String, ValueSet.ValueSetExpansionContainsComponent>();
  private ValueSet focus;

	private ValueSetExpanderFactory factory;
  
  public ValueSetExpanderSimple(WorkerContext context, ValueSetExpanderFactory factory) {
    super();
    this.context = context;
    this.factory = factory;
  }
  
  @Override
  public ValueSetExpansionOutcome expand(ValueSet source) {

    try {
      focus = source.copy();
      focus.setExpansion(new ValueSet.ValueSetExpansionComponent());
      focus.getExpansion().setTimestampSimple(DateAndTime.now());


      handleDefine(source);
      if (source.getCompose() != null) 
        handleCompose(source.getCompose());

      for (ValueSetExpansionContainsComponent c : codes) {
        if (map.containsKey(key(c))) {
          focus.getExpansion().getContains().add(c);
        }
      }
      return new ValueSetExpansionOutcome(focus, null);
    } catch (Exception e) {
      // well, we couldn't expand, so we'll return an interface to a checker that can check membership of the set
      // that might fail too, but it might not, later.
      return new ValueSetExpansionOutcome(new ValueSetCheckerSimple(source, factory, context), e.getMessage());
    }
  }

	private void handleCompose(ValueSetComposeComponent compose) throws Exception {
  	for (UriType imp : compose.getImport()) 
  		importValueSet(imp.getValue());
  	for (ConceptSetComponent inc : compose.getInclude()) 
  		includeCodes(inc);
  	for (ConceptSetComponent inc : compose.getExclude()) 
  		excludeCodes(inc);

  }

	private void importValueSet(String value) throws Exception {
	  if (value == null)
	  	throw new Exception("unable to find value set with no identity");
	  ValueSet vs = context.getValueSets().get(value).getResource();
	  if (vs == null)
			throw new Exception("Unable to find imported value set "+value);
	  ValueSetExpansionOutcome vso = factory.getExpander().expand(vs);
	  if (vso.getService() != null)
      throw new Exception("Unable to expand imported value set "+value);
	  for (ValueSetExpansionContainsComponent c : vso.getValueset().getExpansion().getContains()) {
	  	addCode(c.getSystemSimple(), c.getCodeSimple(), c.getDisplaySimple());
	  }	  
  }

	private void includeCodes(ConceptSetComponent inc) throws Exception {
	  if (context.getTerminologyServices() != null && context.getTerminologyServices().supportsSystem(inc.getSystemSimple())) {
        addCodes(context.getTerminologyServices().expandVS(inc));
      return;
	  }
	    
	  AtomEntry<ValueSet> ae = context.getCodeSystems().get(inc.getSystemSimple());
	  if (ae == null)
      throw new Exception("unable to find code system "+inc.getSystemSimple().toString());
    ValueSet cs = ae.getResource();
	  if (inc.getConcept().size() == 0 && inc.getFilter().size() == 0) {
	    // special case - add all the code system
	    for (ConceptDefinitionComponent def : cs.getDefine().getConcept()) {
        addCodeAndDescendents(inc.getSystemSimple(), def);
	    }
	  }
	    
	  for (ConceptReferenceComponent c : inc.getConcept()) {
	  	addCode(inc.getSystemSimple(), c.getCodeSimple(), Utilities.noString(c.getDisplaySimple()) ? getCodeDisplay(cs, c.getCodeSimple()) : c.getDisplaySimple());
	  }
	  if (inc.getFilter().size() > 1)
	    throw new Exception("Multiple filters not handled yet"); // need to and them, and this isn't done yet. But this shouldn't arise in non loinc and snomed value sets
    if (inc.getFilter().size() == 1) {
	    ConceptSetFilterComponent fc = inc.getFilter().get(0);
	  	if ("concept".equals(fc.getPropertySimple()) && fc.getOpSimple() == FilterOperator.isa) {
	  		// special: all non-abstract codes in the target code system under the value
	  		ConceptDefinitionComponent def = getConceptForCode(cs.getDefine().getConcept(), fc.getValueSimple());
	  		if (def == null)
	  			throw new Exception("Code '"+fc.getValueSimple()+"' not found in system '"+inc.getSystemSimple()+"'");
	  		addCodeAndDescendents(inc.getSystemSimple(), def);
	  	} else
	  		throw new Exception("not done yet");
	  }
  }

	private void addCodes(List<ValueSetExpansionContainsComponent> expand) throws Exception {
	  if (expand.size() > 500) 
	    throw new ETooCostly("Too many codes to display (>"+Integer.toString(expand.size())+")");
    for (ValueSetExpansionContainsComponent c : expand) {
      addCode(c.getSystemSimple(), c.getCodeSimple(), c.getDisplaySimple());
    }   
  }

	private void addCodeAndDescendents(String system, ConceptDefinitionComponent def) {
		if (!ToolingExtensions.hasDeprecated(def)) {  
			if (def.getAbstract() == null || !def.getAbstractSimple())
				addCode(system, def.getCodeSimple(), def.getDisplaySimple());
			for (ConceptDefinitionComponent c : def.getConcept()) 
				addCodeAndDescendents(system, c);
		}
  }

	private void excludeCodes(ConceptSetComponent inc) throws Exception {
	  ValueSet cs = context.getCodeSystems().get(inc.getSystemSimple().toString()).getResource();
	  if (cs == null)
	  	throw new Exception("unable to find value set "+inc.getSystemSimple().toString());
    if (inc.getConcept().size() == 0 && inc.getFilter().size() == 0) {
      // special case - add all the code system
//      for (ConceptDefinitionComponent def : cs.getDefine().getConcept()) {
//!!!!        addCodeAndDescendents(inc.getSystemSimple(), def);
//      }
    }
      

	  for (ConceptReferenceComponent c : inc.getConcept()) {
	  	// we don't need to check whether the codes are valid here- they can't have gotten into this list if they aren't valid
	  	map.remove(key(inc.getSystemSimple(), c.getCodeSimple()));
	  }
	  if (inc.getFilter().size() > 0)
	  	throw new Exception("not done yet");
  }

	
	private String getCodeDisplay(ValueSet cs, String code) throws Exception {
		ConceptDefinitionComponent def = getConceptForCode(cs.getDefine().getConcept(), code);
		if (def == null)
			throw new Exception("Unable to find code '"+code+"' in code system "+cs.getDefine().getSystemSimple());
		return def.getDisplaySimple();
  }

	private ConceptDefinitionComponent getConceptForCode(List<ConceptDefinitionComponent> clist, String code) {
		for (ConceptDefinitionComponent c : clist) {
			if (code.equals(c.getCodeSimple()))
			  return c;
			ConceptDefinitionComponent v = getConceptForCode(c.getConcept(), code);   
			if (v != null)
			  return v;
		}
		return null;
  }
	
	private void handleDefine(ValueSet vs) {
	  if (vs.getDefine() != null) {
      // simple case: just generate the return
    	for (ConceptDefinitionComponent c : vs.getDefine().getConcept()) 
    		addDefinedCode(vs, vs.getDefine().getSystemSimple(), c);
   	}
  }

	private String key(ValueSetExpansionContainsComponent c) {
		return key(c.getSystemSimple(), c.getCodeSimple());
	}

	private String key(String uri, String code) {
		return "{"+uri+"}"+code;
	}

	private void addDefinedCode(ValueSet vs, String system, ConceptDefinitionComponent c) {
		if (!ToolingExtensions.hasDeprecated(c)) { 

			if (c.getAbstract() == null || !c.getAbstractSimple()) {
				addCode(system, c.getCodeSimple(), c.getDisplaySimple());
			}
			for (ConceptDefinitionComponent g : c.getConcept()) 
				addDefinedCode(vs, vs.getDefine().getSystemSimple(), g);
		}
  }

	private void addCode(String system, String code, String display) {
		ValueSetExpansionContainsComponent n = new ValueSet.ValueSetExpansionContainsComponent();
		n.setSystemSimple(system);
	  n.setCodeSimple(code);
	  n.setDisplaySimple(display);
	  String s = key(n);
	  if (!map.containsKey(s)) { 
	  	codes.add(n);
	  	map.put(s, n);
	  }
  }

  
}
