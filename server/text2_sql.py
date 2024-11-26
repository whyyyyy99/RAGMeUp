import psycopg2
from transformers import AutoTokenizer, AutoModelForSeq2SeqLM

class TextToSQL:
    def __init__(self, model_name="suriya7/t5-base-text-to-sql", db_uri=None):
        self.tokenizer = AutoTokenizer.from_pretrained(model_name)
        self.model = AutoModelForSeq2SeqLM.from_pretrained(model_name)
        self.db_uri = db_uri

    def translate(self, user_query):
        input_text = f"translate English to SQL: {user_query}"
        input_ids = self.tokenizer.encode(input_text, return_tensors="pt")
        outputs = self.model.generate(input_ids, max_length=128, num_beams=4, early_stopping=True)
        return self.tokenizer.decode(outputs[0], skip_special_tokens=True)

    def execute(self, sql_query):
        try:
            connection = psycopg2.connect(self.db_uri)
            cursor = connection.cursor()
            cursor.execute(sql_query)
            results = cursor.fetchall()
            connection.close()
            return results
        except Exception as e:
            return {"error": str(e)}
