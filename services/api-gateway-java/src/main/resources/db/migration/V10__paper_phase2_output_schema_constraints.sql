UPDATE prompt_versions pv
JOIN prompt_templates pt ON pt.id = pv.template_id
SET pv.output_schema_json = '{
  "type":"object",
  "required":["topic","topicRefined","researchQuestions","chapters"],
  "properties":{
    "topic":{"type":"string"},
    "topicRefined":{"type":"string"},
    "researchQuestions":{"type":"array","items":{"type":"string"}},
    "chapters":{
      "type":"array",
      "items":{
        "type":"object",
        "required":["title","summary","sections"],
        "properties":{
          "title":{"type":"string"},
          "summary":{"type":"string"},
          "sections":{
            "type":"array",
            "items":{
              "type":"object",
              "required":["title","coreArgument","method","dataPlan","expectedResult","citations"],
              "properties":{
                "title":{"type":"string"},
                "coreArgument":{"type":"string"},
                "method":{"type":"string"},
                "dataPlan":{"type":"string"},
                "expectedResult":{"type":"string"},
                "citations":{"type":"array","items":{"type":"string"}}
              }
            }
          }
        }
      }
    }
  }
}'
WHERE pt.template_key = 'paper_outline_expand'
  AND pv.version_no = 1;

UPDATE prompt_versions pv
JOIN prompt_templates pt ON pt.id = pv.template_id
SET pv.output_schema_json = '{
  "type":"object",
  "required":["logicClosedLoop","methodConsistency","citationVerifiability","overallScore","issues"],
  "properties":{
    "logicClosedLoop":{"type":"boolean"},
    "methodConsistency":{"type":"string"},
    "citationVerifiability":{"type":"string"},
    "overallScore":{"type":"integer","minimum":0,"maximum":100},
    "issues":{
      "type":"array",
      "items":{
        "type":"object",
        "required":["field","severity","suggestion","message"],
        "properties":{
          "field":{"type":"string"},
          "severity":{"type":"string","enum":["high","medium","low"]},
          "suggestion":{"type":"string"},
          "message":{"type":"string"}
        }
      }
    }
  }
}'
WHERE pt.template_key = 'paper_outline_quality_check'
  AND pv.version_no = 1;
