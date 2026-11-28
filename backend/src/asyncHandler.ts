import type { NextFunction, Request, RequestHandler, Response } from 'express';

export function asyncHandler<P = Request['params']>(handle: (req: Request<P>, res: Response, next: NextFunction) => Promise<void>): RequestHandler<P> {
  return (req, res, next) => {
    handle(req, res, next).catch((error: unknown) => { next(error); });
  };
}
