package model

type Error struct {
	Code    string `json:"code"`
	Message string `json:"message"`
}

func Errors(code, message string) []Error {
	return []Error{{Code: code, Message: message}}
}

func ValidationErrors(msgs []string) []Error {
	errs := make([]Error, len(msgs))
	for i, m := range msgs {
		errs[i] = Error{Code: "AccessControl.ValidationFailed", Message: m}
	}
	return errs
}
